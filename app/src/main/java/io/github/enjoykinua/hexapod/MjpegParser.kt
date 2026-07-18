package io.github.enjoykinua.hexapod

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * Reiner, framework-freier MJPEG-Parser (Phase 4, Plan-[ADR-4]) — zerlegt einen
 * `multipart/x-mixed-replace`-Stream in einzelne JPEG-`ByteArray`s. **Kein Android, kein OkHttp**
 * → per JUnit mit einem Fake-[InputStream] testbar ([MjpegParserTest], inkl. Split-Reads).
 *
 * Deckt beide am Server möglichen Formen ab (ob je Frame ein `Content-Length` kommt, wird im
 * Prototyp P4A.1 gegen den Live-Stream verifiziert — der Parser braucht die Antwort nicht):
 * - **Content-Length vorhanden → Fast-Path:** exakt so viele Bytes lesen.
 * - **kein Content-Length → Boundary-Scan:** bis zur nächsten Boundary puffern (binärsicher).
 *
 * `boundaryHint` = der aus dem `Content-Type`-Header gezogene Boundary (ohne führende `--`, siehe
 * [extractBoundary]); `null`/leer → aus der ersten Boundary-Zeile des Streams entdecken.
 *
 * Blockiert bis EOF (Fake-Stream im Test) **oder** bis der Aufrufer den zugrunde liegenden Socket
 * schließt (dann wirft `read()` → die Schleife endet). Die Exception wird **nicht** selbst
 * gefangen — der Aufrufer ([MjpegStream]) fängt sie.
 */
fun parseMjpegStream(
    input: InputStream,
    boundaryHint: String?,
    onFrame: (ByteArray) -> Unit,
) {
    val hinted = boundaryHint?.trim()?.removePrefix("--")?.ifEmpty { null }

    // 1) Zur ersten Boundary-Zeile vorlaufen (Preamble/Leerzeilen überspringen).
    var line = readAsciiLine(input) ?: return
    while (!line.startsWith("--")) {
        line = readAsciiLine(input) ?: return
    }
    // Boundary bestimmen: dem Hint nur trauen, wenn die erste Boundary-Zeile ihn **bestätigt** —
    // sonst dem Body glauben (schützt gegen einen falschen/nicht-RFC-konformen Content-Type-Hint;
    // genau das Restrisiko, das P4A.1 gegen den Live-Stream absichert).
    val discovered = line.removePrefix("--").removeSuffix("--").trim()
    val boundary = if (hinted != null && line.startsWith("--$hinted")) hinted else discovered
    val boundaryLine = "--$boundary"                       // Textzeile (ohne CRLF)
    val scanDelim = "\r\n$boundaryLine".toByteArray(Charsets.ISO_8859_1)   // Bytes (mit CRLF davor)

    while (true) {
        if (line == "$boundaryLine--") return              // Abschluss-Boundary → fertig

        // 2) Part-Header bis Leerzeile lesen; Content-Length merken (falls vorhanden).
        var contentLength = -1
        var header = readAsciiLine(input) ?: return
        while (header.isNotEmpty()) {
            if (header.startsWith("content-length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: -1
            }
            header = readAsciiLine(input) ?: return
        }

        // 3) Frame lesen + auf die nächste Boundary-Zeile vorrücken.
        if (contentLength >= 0) {
            val frame = readExactly(input, contentLength) ?: return
            onFrame(frame)
            // Nach dem Frame folgt CRLF + Boundary-Zeile; Leerzeile(n) überspringen.
            line = readAsciiLine(input) ?: return
            while (line.isEmpty()) line = readAsciiLine(input) ?: return
        } else {
            // Bis "\r\n--boundary" scannen; der Rest der Zeile ("" oder "--") entscheidet weiter.
            val frame = readUntilDelimiter(input, scanDelim) ?: return
            onFrame(frame)
            line = boundaryLine + (readAsciiLine(input) ?: "")
        }
    }
}

/**
 * Eine Zeile als ASCII/ISO-8859-1 lesen (bis `\n`, trailing `\r` entfernt). `null` nur bei EOF
 * **ohne** gelesene Bytes. **Nur für Header-/Boundary-Zeilen** — nie über JPEG-Bytes (die kommen
 * über [readExactly]/[readUntilDelimiter]).
 */
private fun readAsciiLine(input: InputStream): String? {
    val sb = StringBuilder()
    var sawAny = false
    while (true) {
        val r = input.read()
        if (r == -1) return if (sawAny) sb.toString() else null
        sawAny = true
        when (r) {
            '\n'.code -> return sb.toString()
            '\r'.code -> {}                 // verschlucken
            else -> sb.append(r.toChar())
        }
    }
}

/** Genau `n` Bytes lesen (robust gegen Split-Reads). `null`, wenn vorher EOF. */
private fun readExactly(input: InputStream, n: Int): ByteArray? {
    if (n == 0) return ByteArray(0)
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = input.read(buf, off, n - off)
        if (r == -1) return null
        off += r
    }
    return buf
}

/**
 * Bytes lesen, bis der Delimiter (`\r\n--boundary`) vollständig konsumiert ist; gibt alles
 * **davor** zurück (= das JPEG, ohne den trailing CRLF). `null`, wenn vorher EOF. Binärsicher:
 * ein evtl. teilweiser Delimiter im Bildinhalt wird korrekt zurückgerollt ([PushbackInputStream]).
 */
private fun readUntilDelimiter(input: InputStream, delim: ByteArray): ByteArray? {
    val pb = PushbackInputStream(input, delim.size + 1)
    val out = ByteArrayOutputStream()
    var matched = 0
    while (true) {
        val r = pb.read()
        if (r == -1) return null
        val b = r.toByte()
        if (b == delim[matched]) {
            matched++
            if (matched == delim.size) return out.toByteArray()   // Delimiter komplett → fertig
        } else if (matched > 0) {
            // Fehlmatch: erstes Byte des Teilmatches gehört zum Bild; Rest + aktuelles Byte
            // zurückrollen und ab 0 neu prüfen (naiver, aber korrekter Backtrack).
            out.write(delim[0].toInt())
            pb.unread(r)
            for (k in matched - 1 downTo 1) pb.unread(delim[k].toInt() and 0xFF)
            matched = 0
        } else {
            out.write(r)
        }
    }
}
