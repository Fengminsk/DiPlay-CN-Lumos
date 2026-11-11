package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CloudLogArchiveTest {
    @Test fun aPartialLineWaitsForItsNewline() {
        val file = File.createTempFile("diplay-log", ".log").apply {
            writeText("one\ntwo\nhalf a li")
            deleteOnExit()
        }
        val chunk = CloudLogArchive.readCompleteLines(file, 0)!!
        assertEquals("one\ntwo\n", chunk.first)
        assertEquals(8L, chunk.second)
    }

    @Test fun theNextUploadStartsWhereTheLastOneEnded() {
        val file = File.createTempFile("diplay-log", ".log").apply {
            writeText("one\n")
            deleteOnExit()
        }
        val first = CloudLogArchive.readCompleteLines(file, 0)!!
        assertEquals("one\n", first.first)
        file.appendText("two\n")
        val second = CloudLogArchive.readCompleteLines(file, first.second)!!
        assertEquals("two\n", second.first)
        assertEquals(file.length(), second.second)
        assertNull(CloudLogArchive.readCompleteLines(file, second.second))
    }

    @Test fun aFileWithoutNewlinesUploadsNothingYet() {
        val file = File.createTempFile("diplay-log", ".log").apply {
            writeText("still being written")
            deleteOnExit()
        }
        assertNull(CloudLogArchive.readCompleteLines(file, 0))
    }
}
