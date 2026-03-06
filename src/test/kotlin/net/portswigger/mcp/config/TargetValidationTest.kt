package net.portswigger.mcp.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TargetValidationTest {
    @Test
    fun `text body should be truncated by maxTextChars`() {
        val payload = "A".repeat(10)
        val body =
            serializeBody(
                bytes = payload.toByteArray(),
                contentType = "text/plain",
                includeBinary = false,
                maxTextChars = 4,
                textOverflowMode = TextOverflowMode.TRUNCATE,
                maxBinaryBytes = 10,
            )

        assertNotNull(body)
        assertEquals(BodyEncoding.TEXT, body!!.encoding)
        assertEquals(true, body.truncated)
        assertEquals("AAAA", body.text)
    }

    @Test
    fun `binary body should be omitted when includeBinary false`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02)
        val body =
            serializeBody(
                bytes = bytes,
                contentType = "image/png",
                includeBinary = false,
                maxTextChars = 100,
                textOverflowMode = TextOverflowMode.TRUNCATE,
                maxBinaryBytes = 10,
            )

        assertNotNull(body)
        assertEquals(BodyEncoding.OMITTED, body!!.encoding)
        assertEquals("binary body omitted (includeBinary=false)", body.omittedReason)
    }

    @Test
    fun `binary body should be base64 when includeBinary true`() {
        val bytes = byteArrayOf(1, 2, 3)
        val body =
            serializeBody(
                bytes = bytes,
                contentType = "application/octet-stream",
                includeBinary = true,
                maxTextChars = 100,
                textOverflowMode = TextOverflowMode.TRUNCATE,
                maxBinaryBytes = 10,
            )

        assertNotNull(body)
        assertEquals(BodyEncoding.BASE64, body!!.encoding)
        assertEquals("AQID", body.base64)
    }

    @Test
    fun `empty payload should serialize as empty text`() {
        val body =
            serializeBody(
                bytes = byteArrayOf(),
                contentType = "text/plain",
                includeBinary = true,
                maxTextChars = 10,
                textOverflowMode = TextOverflowMode.TRUNCATE,
                maxBinaryBytes = 10,
            )

        assertNotNull(body)
        assertEquals(BodyEncoding.TEXT, body!!.encoding)
        assertEquals("", body.text)
        assertNull(body.base64)
    }

    @Test
    fun `text overflow mode omit should hide oversized text`() {
        val body =
            serializeBody(
                bytes = "A".repeat(32).toByteArray(),
                contentType = "application/javascript",
                includeBinary = false,
                maxTextChars = 8,
                textOverflowMode = TextOverflowMode.OMIT,
                maxBinaryBytes = 10,
            )

        assertNotNull(body)
        assertEquals(BodyEncoding.OMITTED, body!!.encoding)
        assertEquals("text body omitted (exceeds max chars limit)", body.omittedReason)
    }

    @Test
    fun `normalized http serialization should default to omit oversized text`() {
        val options =
            HttpSerializationOptionsInput(
                maxTextBodyChars = 128,
            ).normalized()

        assertEquals(128, options.maxRequestBodyChars)
        assertEquals(128, options.maxResponseBodyChars)
        assertEquals(TextOverflowMode.OMIT, options.textOverflowMode)
    }

    @Test
    fun `normalized http serialization baseline defaults should match contract`() {
        val options = HttpSerializationOptionsInput().normalized()

        assertEquals(1024, options.maxRequestBodyChars)
        assertEquals(1024, options.maxResponseBodyChars)
        assertEquals(65_536, options.maxBinaryBodyBytes)
        assertEquals(TextOverflowMode.OMIT, options.textOverflowMode)
    }
}
