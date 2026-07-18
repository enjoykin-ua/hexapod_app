package io.github.enjoykinua.hexapod

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Unit-Tests des reinen MJPEG-Parsers ([parseMjpegStream], Plan-[ADR-4]). Kein Android/OkHttp.
 * Deckt beide Framing-Formen (mit/ohne Content-Length) + **Split-Reads** + Boundary-Entdeckung ab.
 * Bewusst NICHT hier: BitmapFactory/OkHttp-Streaming ([MjpegStream]) — Integration/Prototyp P4A.1.
 */
class MjpegParserTest {

    private val boundary = "myboundary"

    /** Zwei „JPEGs" mit Bytes, die Delimiter-Präfixe enthalten (Resync im Scan-Modus testen). */
    private val frame1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x0D, 0x0A, 0x2D, 0x41, 0xFF.toByte(), 0xD9.toByte())
    private val frame2 = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)

    private fun buildBody(withLength: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        for (f in listOf(frame1, frame2)) {
            w("--$boundary\r\n")
            w("Content-Type: image/jpeg\r\n")
            if (withLength) w("Content-Length: ${f.size}\r\n")
            w("\r\n")
            out.write(f)
            w("\r\n")
        }
        w("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun collect(input: InputStream, hint: String?): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        parseMjpegStream(input, hint) { frames.add(it) }
        return frames
    }

    @Test fun parses_two_frames_with_content_length() {
        val frames = collect(ByteArrayInputStream(buildBody(withLength = true)), boundary)
        assertEquals(2, frames.size)
        assertArrayEquals(frame1, frames[0])
        assertArrayEquals(frame2, frames[1])
    }

    @Test fun parses_two_frames_without_content_length_via_boundary_scan() {
        val frames = collect(ByteArrayInputStream(buildBody(withLength = false)), boundary)
        assertEquals(2, frames.size)
        assertArrayEquals(frame1, frames[0])
        assertArrayEquals(frame2, frames[1])
    }

    @Test fun discovers_boundary_when_no_hint() {
        val frames = collect(ByteArrayInputStream(buildBody(withLength = true)), null)
        assertEquals(2, frames.size)
        assertArrayEquals(frame1, frames[0])
        assertArrayEquals(frame2, frames[1])
    }

    @Test fun survives_split_reads_length_path() {
        val frames = collect(ThrottledStream(buildBody(withLength = true), 1), boundary)
        assertEquals(2, frames.size)
        assertArrayEquals(frame1, frames[0])
        assertArrayEquals(frame2, frames[1])
    }

    @Test fun survives_split_reads_scan_path() {
        val frames = collect(ThrottledStream(buildBody(withLength = false), 1), boundary)
        assertEquals(2, frames.size)
        assertArrayEquals(frame1, frames[0])
        assertArrayEquals(frame2, frames[1])
    }

    @Test fun ignores_wrong_hint_and_uses_body_boundary() {
        // Content-Type-Hint stimmt NICHT mit der echten Body-Boundary überein -> Body gewinnt.
        val frames = collect(ByteArrayInputStream(buildBody(withLength = true)), "totally-wrong-hint")
        assertEquals(2, frames.size)
        assertArrayEquals(frame1, frames[0])
        assertArrayEquals(frame2, frames[1])
    }

    @Test fun tolerates_leading_crlf_preamble() {
        val body = "\r\n".toByteArray(Charsets.ISO_8859_1) + buildBody(withLength = true)
        val frames = collect(ByteArrayInputStream(body), boundary)
        assertEquals(2, frames.size)
        assertArrayEquals(frame1, frames[0])
    }

    /** [InputStream], der pro `read(buf,off,len)` höchstens `max` Bytes liefert (Split-Reads). */
    private class ThrottledStream(data: ByteArray, private val max: Int) : InputStream() {
        private val delegate = ByteArrayInputStream(data)
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, minOf(len, max))
    }
}
