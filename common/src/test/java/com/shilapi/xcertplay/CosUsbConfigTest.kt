package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json is only real under Robolectric, and the parser is what these tests exercise.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CosUsbConfigTest {
    private val valid = """{"bucket":"demo-12345","endpoint":"cos.ap-guangzhou.myqcloud.com",
        "secret_id":"id","secret_key":"key"}"""

    @Test fun namedFileWinsOverOtherJsonWhateverTheListingOrder() {
        assertEquals(listOf("DiPlay-COS.json", "backup.json", "z-notes.json"),
            CosUsbConfig.preferredJson(listOf("z-notes.json", "backup.json", "DiPlay-COS.json")))
        // The documented name is matched case-insensitively and still sorts first.
        assertEquals("DiPlay-COS.JSON",
            CosUsbConfig.preferredJson(listOf("a.json", "DiPlay-COS.JSON")).first())
    }

    @Test fun onlyJsonFilesBecomeCandidates() {
        assertEquals(listOf("b.json"), CosUsbConfig.preferredJson(listOf("a.txt", "b.json", "c.log")))
        assertTrue(CosUsbConfig.preferredJson(listOf("notes.txt", "photo.jpg")).isEmpty())
    }

    @Test fun anInvalidFileIsSkippedInsteadOfFailingTheImport() {
        val picked = CosUsbConfig.pick(listOf("broken.json" to "{not json", "ok.json" to valid), enabled = false)
        assertEquals("demo-12345", picked?.bucket)
        assertEquals("cos.ap-guangzhou.myqcloud.com", picked?.endpoint)
    }

    @Test fun theFileNeverSwitchesUploadOnByItself() {
        assertFalse(CosUsbConfig.pick(listOf("ok.json" to valid), enabled = false)!!.enabled)
        // A driver who already enabled upload keeps it enabled across a re-plug.
        assertTrue(CosUsbConfig.pick(listOf("ok.json" to valid), enabled = true)!!.enabled)
    }

    @Test fun unrelatedJsonIsRejected() {
        assertNull(CosUsbConfig.pick(listOf("theme.json" to """{"name":"night","color":"#000"}"""), enabled = false))
        assertNull(CosUsbConfig.pick(emptyList(), enabled = false))
    }

    @Test fun snakeAndCamelCaseKeysBothImport() {
        val camel = """{"bucket":"demo-12345","endpoint":"https://cos.ap-shanghai.myqcloud.com/",
            "secretId":"id","secretKey":"key"}"""
        assertEquals("cos.ap-shanghai.myqcloud.com",
            CosUsbConfig.pick(listOf("a.json" to camel), enabled = false)?.endpoint)
    }
}
