package net.portswigger.mcp.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SerializationBehaviorTest {
    @Test
    fun `serializeBody should omit oversized text when overflow mode is omit`() {
        val payload = "abcdefghijklmnopqrstuvwxyz".toByteArray()

        val result =
            serializeBody(
                bytes = payload,
                contentType = "application/json",
                includeBinary = false,
                maxTextChars = 10,
                textOverflowMode = TextOverflowMode.OMIT,
                maxBinaryBytes = 1024,
            )

        assertNotNull(result)
        assertEquals(BodyEncoding.OMITTED, result!!.encoding)
        assertTrue(result.omittedReason?.contains("max chars", ignoreCase = true) == true)
    }

    @Test
    fun `serializeBody should truncate oversized text when overflow mode is truncate`() {
        val payload = "abcdefghijklmnopqrstuvwxyz".toByteArray()

        val result =
            serializeBody(
                bytes = payload,
                contentType = "application/json",
                includeBinary = false,
                maxTextChars = 10,
                textOverflowMode = TextOverflowMode.TRUNCATE,
                maxBinaryBytes = 1024,
            )

        assertNotNull(result)
        assertEquals(BodyEncoding.TEXT, result!!.encoding)
        assertEquals(true, result.truncated)
        assertEquals(10, result.text?.length)
    }

    @Test
    fun `serializeBody should omit binary when includeBinary is false`() {
        val payload = byteArrayOf(0x00, 0x01, 0x02, 0x03)

        val result =
            serializeBody(
                bytes = payload,
                contentType = null,
                includeBinary = false,
                maxTextChars = 1024,
                maxBinaryBytes = 1024,
            )

        assertNotNull(result)
        assertEquals(BodyEncoding.OMITTED, result!!.encoding)
        assertTrue(result.omittedReason?.contains("includeBinary=false") == true)
    }

    @Test
    fun `serializeBody should enforce max binary size`() {
        val payload = byteArrayOf(0x00, 0x01, 0x02, 0x03)

        val result =
            serializeBody(
                bytes = payload,
                contentType = null,
                includeBinary = true,
                maxTextChars = 1024,
                maxBinaryBytes = 2,
            )

        assertNotNull(result)
        assertEquals(BodyEncoding.OMITTED, result!!.encoding)
        assertTrue(result.omittedReason?.contains("maxBinaryBodyBytes") == true)
    }

    @Test
    fun `serializeBody should encode binary payload to base64 when allowed`() {
        val payload = byteArrayOf(0x00, 0x01, 0x02, 0x03)

        val result =
            serializeBody(
                bytes = payload,
                contentType = null,
                includeBinary = true,
                maxTextChars = 1024,
                maxBinaryBytes = 1024,
            )

        assertNotNull(result)
        assertEquals(BodyEncoding.BASE64, result!!.encoding)
        assertNotNull(result.base64)
    }

    @Test
    fun `serialization normalization should honor per-body overrides and clamp negatives`() {
        val options =
            HttpSerializationOptionsInput(
                maxTextBodyChars = -10,
                maxRequestBodyChars = -1,
                maxResponseBodyChars = 20,
                maxBinaryBodyBytes = -1,
            ).normalized()

        assertEquals(0, options.maxRequestBodyChars)
        assertEquals(20, options.maxResponseBodyChars)
        assertEquals(20, options.maxRawBodyChars)
        assertEquals(0, options.maxBinaryBodyBytes)
    }
}
