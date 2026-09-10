package com.classguard.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * 国产 ROM 自启动/后台管理页一键跳转（v1.2）。
 *
 * 按 Build.MANUFACTURER/BRAND 识别厂商，映射各家自启动管理页组件名；
 * 组件不存在（ROM 改版、非国产机）时回退到应用详情页，永不抛出。
 */
object RomGuides {

    sealed class Vendor(val id: String, val label: String) {
        data object Xiaomi : Vendor("xiaomi", "小米 / Redmi")
        data object Huawei : Vendor("huawei", "华为 / 荣耀")
        data object Oppo : Vendor("oppo", "OPPO / 一加 / realme")
        data object Vivo : Vendor("vivo", "vivo / iQOO")
        data object Other : Vendor("other", "其他")
    }

    fun detectVendor(): Vendor {
        val s = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return when {
            "xiaomi" in s || "redmi" in s -> Vendor.Xiaomi
            "huawei" in s || "honor" in s -> Vendor.Huawei
            "oppo" in s || "oneplus" in s || "realme" in s -> Vendor.Oppo
            "vivo" in s || "iqoo" in s -> Vendor.Vivo
            else -> Vendor.Other
        }
    }

    /** 各厂商自启动管理页组件（按优先级排列，均需运行时存在性检查）。 */
    private val VENDOR_COMPONENTS: Map<Vendor, List<Pair<String, String>>> = mapOf(
        Vendor.Xiaomi to listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        Vendor.Huawei to listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        ),
        Vendor.Oppo to listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
        Vendor.Vivo to listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        ),
    )

    /** 自启动管理页 Intent；全部不可用则回退应用详情页。 */
    fun autoStartIntent(context: Context): Intent {
        val candidates = VENDOR_COMPONENTS[detectVendor()].orEmpty()
        val pm = context.packageManager
        for ((pkg, cls) in candidates) {
            runCatching {
                val intent = Intent().setComponent(ComponentName(pkg, cls))
                if (intent.resolveActivity(pm) != null) return intent
            }
        }
        return appDetailsIntent(context)
    }

    fun appDetailsIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 使用提示卡里按当前 ROM 动态渲染的定制步骤。 */
    fun steps(): List<String> = when (detectVendor()) {
        Vendor.Xiaomi -> listOf(
            "打开 设置 → 应用设置 → 应用管理 → 课堂哨兵 → 开启「自启动」",
            "同一页面把省电策略改为「无限制」",
            "在最近任务里下拉课堂哨兵卡片，点击锁图标锁定后台",
        )
        Vendor.Huawei -> listOf(
            "打开 设置 → 电池 → 启动管理 → 课堂哨兵",
            "关闭「自动管理」，三项手动开关（自启动/关联启动/后台活动）全部打开",
        )
        Vendor.Oppo -> listOf(
            "打开 设置 → 电池 → 更多设置，允许课堂哨兵在后台运行",
            "设置 → 应用管理 → 课堂哨兵 → 允许自启动与关联启动",
        )
        Vendor.Vivo -> listOf(
            "打开 设置 → 电池 → 后台功耗管理 → 课堂哨兵，允许后台高耗电",
            "设置 → 应用管理 → 课堂哨兵 → 自启动管理，允许自启动",
        )
        Vendor.Other -> listOf(
            "在系统设置中找到电池/启动管理，允许课堂哨兵自启动与后台运行",
        )
    }
}
