package com.shilapi.xcertplay.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AoaIdentityEncodingTest {
    @Test
    fun sdkIdentityPrefixIsTerminatedExactlyOnce() {
        val fields = listOf("ICCOA", "CarLink", "ICCOA CarLink")
        fields.forEach { value ->
            val expected = value.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            assertArrayEquals(expected, AoaIdentityEncoding.encode(value))
            assertEquals(0, AoaIdentityEncoding.encode(value).last().toInt())
        }
    }

    @Test
    fun rejectsOversizedOrEmbeddedZeroPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            AoaIdentityEncoding.encode("a".repeat(256))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AoaIdentityEncoding.encode("a\u0000b")
        }
    }
}
