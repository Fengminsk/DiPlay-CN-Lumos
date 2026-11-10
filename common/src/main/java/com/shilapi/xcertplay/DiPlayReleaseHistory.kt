package com.shilapi.xcertplay

/** Short, newest-first Lumos and CN release notes for the in-page About category. */
internal object DiPlayReleaseHistory {
    data class Entry(val version: String, val date: String, val highlights: List<String>)

    fun parse(markdown: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var version: String? = null
        var date = ""
        val highlights = mutableListOf<String>()

        fun finish() {
            val current = version ?: return
            if (highlights.isNotEmpty()) entries += Entry(current, date, highlights.toList())
            highlights.clear()
        }

        markdown.lineSequence().forEach { raw ->
            val line = raw.trim()
            val heading = Regex("^##\\s+(.+?)\\s+—\\s+(\\d{4}-\\d{2}-\\d{2})").find(line)
            if (heading != null) {
                finish()
                version = heading.groupValues[1]
                date = heading.groupValues[2]
            } else if (version != null && line.startsWith("- ") && highlights.size < 2) {
                val clean = line.removePrefix("- ")
                    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                    .replace("**", "")
                    .replace("`", "")
                    .replace("CN 特性", "功能")
                highlights += if (clean.length > 140) clean.take(139).trimEnd() + "…" else clean
            }
        }
        finish()
        return entries.withIndex().sortedWith { left, right ->
            val byDate = right.value.date.compareTo(left.value.date)
            if (byDate != 0) byDate
            else if (left.value.version.firstOrNull()?.isDigit() == true &&
                right.value.version.firstOrNull()?.isDigit() == true) {
                compareVersions(right.value.version, left.value.version)
            } else left.index.compareTo(right.index)
        }.map { it.value }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = Regex("\\d+").findAll(left).map { it.value.toInt() }.toList()
        val b = Regex("\\d+").findAll(right).map { it.value.toInt() }.toList()
        for (index in 0 until maxOf(a.size, b.size)) {
            val result = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return 0
    }
}
