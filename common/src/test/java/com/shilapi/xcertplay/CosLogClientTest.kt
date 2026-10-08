package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CosLogClientTest {
    @Test fun putSignatureMatchesIndependentFixture() {
        val signature = CosLogClient.authorize("test-id", "test-secret-key",
            "/cars-bridge/device/logs/test.jsonl.gz", mapOf(
                "content-type" to "application/gzip",
                "content-md5" to "CY9rzUYh03PK3k6DJie09g==",
                "host" to "examplebucket-1250000000.cos.ap-guangzhou.myqcloud.com",
            ), "1417773892;1417853898")
        assertTrue(signature.endsWith("q-signature=f7d4d05327f91fc3bef22c1f8ab4fac8cf2728e7"))
        assertTrue(signature.contains("q-header-list=content-md5;content-type;host"))
        assertFalse(signature.contains("test-secret-key"))
    }
}
