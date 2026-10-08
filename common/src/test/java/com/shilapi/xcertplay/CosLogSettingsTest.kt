package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CosLogSettingsTest {
    @Test fun importedConfigIsOptInAndAcceptsCommonFieldNames() {
        val config = CosLogSettings.fromJson("""{"bucket":"demo-12345","endpoint":"https://cos.ap-guangzhou.myqcloud.com/","secret_id":"id","secret_key":"key"}""")
        assertTrue(config.valid())
        assertFalse(config.enabled)
        assertEquals("cos.ap-guangzhou.myqcloud.com", config.endpoint)
    }

    @Test fun nonCosEndpointAndInvalidBucketAreRejected() {
        for (json in listOf(
            """{"bucket":"demo-12345","endpoint":"evil.example","secretId":"id","secretKey":"key"}""",
            """{"bucket":"../demo-12345","endpoint":"cos.ap-guangzhou.myqcloud.com","secretId":"id","secretKey":"key"}""",
        )) {
            assertTrue(runCatching { CosLogSettings.fromJson(json) }.isFailure)
        }
    }
}
