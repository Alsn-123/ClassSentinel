package com.classguard.app.recognition

/**
 * 识别文本修复（展示与转写用，v2.2）。
 *
 * 核心逻辑：解决 ASR 卡顿重复（一个字被识别成 2-3 个，以及词组重复输出两遍）
 *
 * - 同字连发：连续 ≥2 个相同字符折叠为 1 个
 * - 叠词保留：两连的常见叠词字（"谢谢/想想/看看/试试/…"）保留，避免把正常汉语误当卡顿
 * - 词组重复：紧邻相同片段（2~3 字）重复则折叠为一份（"这位这位同学"→"这位同学"）
 *
 * 已知代价：
 * - 正常两连的叠词（"谢谢"）不会折叠（按保留逻辑）；
 * - 三连强调式（"来来来"）和非叠 AABB（"讨论讨论"）会被折叠，语义损失可接受。
 */
object TextRepair {

    /**
     * 两连保留的白名单字符（仅 CJK 生效）——这些字的两连是正常汉语表达，
     * 不是解码卡顿：动词/形容词重叠（看看、慢慢）、亲属称谓与常见叠词名词（妈妈、弟弟）。
     * 三连及以上仍折叠，因为正常汉语极少有三连。
     */
    private val REDUPLICABLE = setOf(
        // 动词/形容词重叠
        '谢', '想', '看', '试', '听', '说', '讲', '读', '写', '问', '找', '学', '做', '帮',
        '聊', '谈', '走', '跳', '唱', '玩', '吃', '喝', '等', '好', '快', '慢', '轻', '渐',
        '常', '刚', '多', '少', '早', '晚', '细', '粗', '深', '浅', '高', '矮', '长', '短',
        // 亲属称谓与常见叠词名词
        '妈', '爸', '爷', '奶', '哥', '姐', '弟', '妹', '叔', '婶', '伯', '姑', '舅', '姨',
        '宝', '娃', '团', '圆', '星', '朵', '片', '块', '条', '串', '堆', '群', '双', '对',
        '人', '天', '年', '日', '月', '家', '户', '村', '乡',
    )

    /**
     * 展示/入库前的整句清理（v2.3）：卡顿折叠 + 英文碎片过滤。
     *
     * 英文碎片从哪来：内置的是中英双语模型（词表含 ▁FI/ANCE/Y 等 BPE 碎片），
     * 远场或口音下会把中文音节"听成"英文单词——实测记录里出现的
     * INANCE(FINANCE)、NDELIEVE(BELIEVE)、MILE、ED、CE、M 全是噪声。
     * 中文课堂场景下这些几乎不可能是有效内容，故默认过滤，让记录可读。
     */
    fun clean(text: String): String = stripLatinFragments(collapseStutter(text))

    /**
     * 去掉拉丁字母片段（含其后的空格）。纯英文句（如整句都是英文）会被清空——
     * 本工具面向中文课堂，这是刻意的取舍。
     */
    fun stripLatinFragments(text: String): String {
        if (text.none { it in 'a'..'z' || it in 'A'..'Z' }) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                // 跳过整段拉丁，并吞掉紧随其后的一个空格（避免留下双空格）
                while (i < text.length && (text[i] in 'a'..'z' || text[i] in 'A'..'Z')) i++
                if (i < text.length && text[i] == ' ') i++
                continue
            }
            sb.append(ch)
            i++
        }
        return sb.toString().trim()
    }

    fun collapseStutter(text: String): String {
        if (text.length < 2) return text

        // 1) 同字连发：连续 ≥2 个相同字符折叠为 1 个；
        //    两连仅叠词白名单字保留，避免把正常汉语叠词误当卡顿。
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            var run = 1
            while (i + run < text.length && text[i + run] == ch) run++
            sb.append(ch)
            // 两连且属于常见叠词 → 保留两份（"谢谢/想想"）；其余情况连发一律折叠为一份
            if (run == 2 && ch.code in 0x4E00..0x9FFF && ch in REDUPLICABLE) sb.append(ch)
            i += run
        }

        // 2) 词组重复：紧挨着的相同 2~3 字片段折叠为一份（"这位这位同学"→"这位同学"）
        val s = sb.toString()
        if (s.length < 4) return s
        val out = StringBuilder(s.length)
        var j = 0
        while (j < s.length) {
            var matched = false
            for (len in 3 downTo 2) {
                if (j + 2 * len <= s.length && s.regionMatches(j, s, j + len, len)) {
                    out.append(s, j, j + len)
                    j += len
                    while (j + len <= s.length && s.regionMatches(j - len, s, j, len)) {
                        j += len
                    }
                    matched = true
                    break
                }
            }
            if (!matched) {
                out.append(s[j])
                j++
            }
        }
        return out.toString()
    }
}