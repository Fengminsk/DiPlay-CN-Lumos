package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DiPlayReleaseHistoryTest {
    @Test fun sortsUnorderedCnVersionsFromLatestToEarliestAndKeepsConciseNotes() {
        val notes = """
            ## 0.2.15-cn.3 — 2026-10-08
            - Third version.
            ## 0.2.7-cn.1 — 2026-09-30
            - The first version.
            ## 0.2.15-cn.7 — 2026-10-09
            - Fixed **CarPlay** [connection](https://example.invalid).
            - Improved `ICCOA`.
            - This longer detail stays in the source document.
            ## 0.2.15-cn.6 — 2026-10-09
            - Sixth version.
        """.trimIndent()

        val entries = DiPlayReleaseHistory.parse(notes)
        assertEquals(listOf("0.2.15-cn.7", "0.2.15-cn.6", "0.2.15-cn.3", "0.2.7-cn.1"),
            entries.map { it.version })
        assertEquals(listOf("Fixed CarPlay connection.", "Improved ICCOA."), entries.first().highlights)
        assertFalse(entries.first().highlights.any { it.contains("longer detail") })
    }

    @Test fun bundledHistoryInterleavesLumosAndCnByDateAndEndsAtTheFirstCnRelease() {
        val assets = RuntimeEnvironment.getApplication().assets
        val lumos = assets.open("LUMOS_CHANGELOG.md").bufferedReader().use { it.readText() }
        val cn = assets.open("CN_OPTIMIZATIONS.md").bufferedReader().use { it.readText() }
        val entries = DiPlayReleaseHistory.parse("$lumos\n$cn")
        assertEquals("Lumos · 采纳 CN 新版修复", entries.first().version)
        assertEquals("0.2.15-cn.7", entries.first { it.version.startsWith("0.") }.version)
        assertEquals("0.2.7-cn.1", entries.last().version)
    }
}
