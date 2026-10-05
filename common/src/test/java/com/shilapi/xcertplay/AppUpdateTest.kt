package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun tagWithLeadingVMatchesVersionName() {
        assertEquals("0.2.11-cn.1", UpdateText.normalizeTag("v0.2.11-cn.1"))
        assertEquals("0.2.11-cn.1", UpdateText.normalizeTag("0.2.11-cn.1"))
        assertFalse(UpdateText.normalizeTag("v0.2.11-cn.2") == UpdateText.normalizeTag("0.2.11-cn.1"))
    }

    @Test
    fun plainNotesKeepsTheUpdateDetails() {
        val markdown = """
            # DiPlay CN 0.2.11-cn.2

            **本版更新**

            - 检查更新弹出发布说明
            - [完整日志](https://example.invalid)
        """.trimIndent()
        val plain = UpdateText.plainNotes(markdown)
        assertTrue(plain.contains("检查更新弹出发布说明"))
        assertTrue(plain.contains("完整日志"))
        assertFalse(plain.contains("https://"))
        assertFalse(plain.contains("**"))
    }
}
