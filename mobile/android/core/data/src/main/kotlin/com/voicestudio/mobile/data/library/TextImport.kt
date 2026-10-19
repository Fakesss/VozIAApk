package com.voicestudio.mobile.data.library

import com.voicestudio.mobile.network.ClientFailure
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Plain text, Markdown and EPUB books become ordered (title, text) chapters. */
object TextImport {
    const val MAX_BYTES = 20 * 1024 * 1024
    private val heading = Regex("""^\s*(#{1,3}\s+\S.{0,120}|(cap[ií]tulo|chapter|parte|part|livro|book|pr[oó]logo|ep[ií]logo|prologue|epilogue|introdu[cç][aã]o|introduction)\b[^\n]{0,100})\s*$""",
        setOf(RegexOption.IGNORE_CASE))

    /** UTF-8 when valid (BOM stripped), otherwise Windows-1252, the common legacy encoding. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw ClientFailure("vozia.audiobook.too_large")
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) { String(body, Charset.forName("windows-1252")) }
            .replace("\r\n", "\n").replace('\r', '\n')
    }

    fun chapters(bytes: ByteArray, name: String, fallbackTitle: (Int) -> String): List<Pair<String, String>> =
        if (name.lowercase().endsWith(".epub") || isZip(bytes)) epub(bytes, fallbackTitle) else split(decode(bytes), fallbackTitle = fallbackTitle)

    /** Headings ("Capítulo 1", "# Parte II", "Prólogo") start chapters; otherwise ~8,000-character parts. */
    fun split(text: String, partSize: Int = 8000, fallbackTitle: (Int) -> String): List<Pair<String, String>> {
        val lines = text.replace("\r\n", "\n").lines()
        val found = mutableListOf<Pair<String, StringBuilder>>()
        var current: Pair<String, StringBuilder>? = null
        for (line in lines) {
            if (heading.matches(line) && line.trim().length <= 120) {
                current = line.trim().trimStart('#').trim() to StringBuilder(); found += current
            } else {
                if (current == null) { current = "" to StringBuilder(); found += current }
                current.second.append(line).append('\n')
            }
        }
        val titled = found.map { (title, body) -> title to body.toString().trim() }.filter { it.second.isNotEmpty() }
        if (titled.size >= 2 || (titled.size == 1 && titled[0].first.isNotEmpty())) {
            return titled.mapIndexed { index, (title, body) -> title.ifEmpty { fallbackTitle(index + 1) } to body }
        }
        val all = text.trim()
        if (all.isEmpty()) return emptyList()
        val parts = mutableListOf<String>(); val buffer = StringBuilder()
        for (paragraph in all.split(Regex("\n\\s*\n"))) {
            if (buffer.isNotEmpty() && buffer.length + paragraph.length > partSize) { parts += buffer.toString().trim(); buffer.setLength(0) }
            buffer.append(paragraph.trim()).append("\n\n")
        }
        if (buffer.isNotBlank()) parts += buffer.toString().trim()
        return parts.mapIndexed { index, body -> fallbackTitle(index + 1) to body }
    }

    private fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /** Spine order from content.opf; each XHTML document is one chapter titled by its first heading. */
    fun epub(bytes: ByteArray, fallbackTitle: (Int) -> String): List<Pair<String, String>> {
        if (bytes.size > MAX_BYTES) throw ClientFailure("vozia.audiobook.too_large")
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val data = zip.readBytes(); total += data.size
                if (total > 4L * MAX_BYTES) throw ClientFailure("vozia.audiobook.too_large")
                files[entry.name] = data
            }
        }
        val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8) ?: throw ClientFailure("vozia.audiobook.unreadable")
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: throw ClientFailure("vozia.audiobook.unreadable")
        val opf = files[opfPath]?.toString(Charsets.UTF_8) ?: throw ClientFailure("vozia.audiobook.unreadable")
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val manifest = Regex("<item\\b[^>]*>").findAll(opf).associate { tag ->
            fun attr(name: String) = Regex("\\b$name=\"([^\"]*)\"").find(tag.value)?.groupValues?.get(1).orEmpty()
            attr("id") to attr("href")
        }
        val spine = Regex("<itemref\\b[^>]*idref=\"([^\"]+)\"").findAll(opf).map { it.groupValues[1] }.toList()
        return spine.mapNotNull { id -> manifest[id]?.let { href -> files[normalize(base + java.net.URLDecoder.decode(href, "UTF-8"))] } }
            .mapNotNull { html -> xhtmlChapter(html.toString(Charsets.UTF_8)) }
            .mapIndexed { index, (title, text) -> title.ifEmpty { fallbackTitle(index + 1) } to text }
    }
    private fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach { when (it) { "", "." -> {}; ".." -> parts.removeLastOrNull(); else -> parts.addLast(it) } }
        return parts.joinToString("/")
    }
    private fun xhtmlChapter(html: String): Pair<String, String>? {
        val body = html.substringAfter("<body", html).substringAfter('>')
        val title = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(body)?.groupValues?.get(1)?.let(::plain)?.trim().orEmpty()
        val text = plain(body.replace(Regex("</(p|div|h[1-6]|li|br)\\s*>|<br\\s*/?>", RegexOption.IGNORE_CASE), "\n\n"))
            .lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
        val withoutTitle = if (title.isNotEmpty() && text.startsWith(title)) text.removePrefix(title).trim() else text
        return if (withoutTitle.length < 2) null else title to withoutTitle
    }
    private fun plain(html: String): String = html.replace(Regex("<script.*?</script>|<style.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { code -> String(Character.toChars(code)) } ?: "" }
}
