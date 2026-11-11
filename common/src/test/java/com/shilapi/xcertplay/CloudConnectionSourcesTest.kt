package com.shilapi.xcertplay

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConnectionSourcesTest {
    @Test
    fun independentConnectionStreamsCannotOverwriteEachOtherInCos() {
        val time = 1_800_000_000_000L
        val session = CloudLogArchive.objectKey("com.shihab.diplay.cn", "diplay.log", time, 0)
        val iccoa = CloudLogArchive.objectKey("com.shihab.diplay.cn", "iccoa-link.log", time, 0)
        val carplay = CloudLogArchive.objectKey("com.shihab.diplay.cn", "carplay-connection.log", time, 0)

        assertNotEquals(session, iccoa)
        assertNotEquals(session, carplay)
        assertNotEquals(iccoa, carplay)
        assertTrue(iccoa.endsWith("-iccoa-link.log"))
        assertTrue(carplay.endsWith("-carplay-connection.log"))
    }
}
