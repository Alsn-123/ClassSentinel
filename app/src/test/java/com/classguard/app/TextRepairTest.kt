package com.classguard.app

import com.classguard.app.recognition.TextRepair
import org.junit.Assert.assertEquals
import org.junit.Test

/** 展示层卡顿折叠：同字连发（≥2 连）、相邻同片段重复、叠词白名单。 */
class TextRepairTest {

    @Test
    fun `下限与短串原样返回`() {
        assertEquals("", TextRepair.collapseStutter(""))
        assertEquals("a", TextRepair.collapseStutter("a"))
        assertEquals("ab", TextRepair.collapseStutter("ab"))
        assertEquals("ab", TextRepair.collapseStutter("aab"))
    }

    @Test
    fun `同字两连折叠_两到三连重复全消失`() {
        // 用户报告"一个字被识别成两到三个字"：两连、三连都要折叠
        assertEquals("动能定理", TextRepair.collapseStutter("动能定定理"))
        assertEquals("动能定理", TextRepair.collapseStutter("动能定定定理"))
        assertEquals("动能定理", TextRepair.collapseStutter("动能定定定定理"))
        assertEquals("你来说你的思路", TextRepair.collapseStutter("你来说说说你的思路"))
        assertEquals("你来说你的思路", TextRepair.collapseStutter("你来说说说说说你的思路"))
        assertEquals("a", TextRepair.collapseStutter("aa"))
        assertEquals("a", TextRepair.collapseStutter("aaaaaa"))
        assertEquals("6", TextRepair.collapseStutter("66666"))
    }

    @Test
    fun `叠词白名单两连保留`() {
        assertEquals("谢谢", TextRepair.collapseStutter("谢谢"))
        assertEquals("想想", TextRepair.collapseStutter("想想"))
        assertEquals("看看", TextRepair.collapseStutter("看看"))
        assertEquals("好好学习", TextRepair.collapseStutter("好好学习"))
        assertEquals("天天向上", TextRepair.collapseStutter("天天向上"))
        assertEquals("慢慢来", TextRepair.collapseStutter("慢慢来"))
        // 亲属称谓等正常叠词名词不应被破坏
        assertEquals("妈妈", TextRepair.collapseStutter("妈妈"))
        assertEquals("爸爸", TextRepair.collapseStutter("爸爸"))
        assertEquals("弟弟和妹妹", TextRepair.collapseStutter("弟弟和妹妹"))
        // 白名单字的三连仍折叠为单字（"好好好"是卡顿或强调，语义损失可接受）
        assertEquals("好", TextRepair.collapseStutter("好好好"))
    }

    @Test
    fun `非叠词两连折叠`() {
        assertEquals("定", TextRepair.collapseStutter("定定"))
        assertEquals("请", TextRepair.collapseStutter("请请"))
        assertEquals("张三", TextRepair.collapseStutter("张张三"))
        assertEquals("a", TextRepair.collapseStutter("aa")) // 字母数字白名单不适用
        assertEquals("a", TextRepair.collapseStutter("aaa"))
    }

    @Test
    fun `相邻同片段重复折叠`() {
        assertEquals("这位同学", TextRepair.collapseStutter("这位这位同学"))
        assertEquals("找个同学", TextRepair.collapseStutter("找个找个同学"))
        assertEquals("讨论", TextRepair.collapseStutter("讨论讨论讨论"))
        assertEquals("我们讨论一下", TextRepair.collapseStutter("我们讨论讨论一下"))
        // 正常语流不受影响
        assertEquals("谁来回答一下", TextRepair.collapseStutter("谁来回答一下"))
    }

    @Test
    fun `模拟真实自测句`() {
        // 模拟器自测 TTS 音频的实际识别输出。
        // "请问请请"：问后的"请请"两连折叠为单"请"，剩"请问请"（连字折叠规则使然）
        assertEquals(
            "同学们下面我们看第四题这道题提考的是第三章的动能定理大家先自己想一想好我找个同学来回答一下这个问题谁来回答请问请这位同学你来说你的思路",
            TextRepair.collapseStutter(
                "同学们下面我们看第四题这道题提考的是第三章的动动能定定定定理大家先自己想一想好好好我找个找个同学来回答一下这个问题谁来回答请问请请这位这位同学你来说说说说你的思路"
            ),
        )
    }
}
