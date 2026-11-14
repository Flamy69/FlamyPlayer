package io.github.flamy69.flamyplayer.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Recognizes video files by name; used where there is no media index, i.e. on network shares. */
object VideoFiles {

    private val extensions = setOf(
        "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mts",
        "mpg", "mpeg", "vob", "flv", "3gp", "wmv", "divx",
    )

    fun isVideo(fileName: String): Boolean = extension(fileName) in extensions

    fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    /** File name without its extension. */
    fun baseName(fileName: String): String = fileName.substringBeforeLast('.').ifEmpty { fileName }
}

/** Finds and decodes the subtitle files that sit next to a video. */
object SubtitleFiles {

    enum class Format(val mimeType: String) {
        SRT("application/x-subrip"),
        SSA("text/x-ssa"),
        VTT("text/vtt"),
    }

    fun format(fileName: String): Format? = when (VideoFiles.extension(fileName)) {
        "srt" -> Format.SRT
        "ass", "ssa" -> Format.SSA
        "vtt" -> Format.VTT
        else -> null
    }

    /**
     * The subtitle files among [siblings] (all file names of one directory) that belong to
     * [videoName]: `Film.srt` and `Film.cs.srt` both belong to `Film.mkv`, but `Film 2.srt` does
     * not if there is a `Film 2.mkv` as well. The plain one comes first.
     */
    fun matching(videoName: String, siblings: Collection<String>): List<String> {
        val base = VideoFiles.baseName(videoName)
        val longerVideoBases = siblings
            .filter { VideoFiles.isVideo(it) }
            .map { VideoFiles.baseName(it) }
            .filter { it.length > base.length }
        return siblings
            .filter { name ->
                if (format(name) == null) return@filter false
                val subtitleBase = VideoFiles.baseName(name)
                belongsTo(subtitleBase, base) && longerVideoBases.none { belongsTo(subtitleBase, it) }
            }
            .sortedWith(compareBy<String> { tag(videoName, it).length }.then(NaturalOrder))
    }

    private fun belongsTo(subtitleBase: String, videoBase: String): Boolean =
        subtitleBase.equals(videoBase, ignoreCase = true) ||
            (subtitleBase.length > videoBase.length &&
                subtitleBase.startsWith(videoBase, ignoreCase = true) &&
                subtitleBase[videoBase.length] in TAG_SEPARATORS)

    /** What a subtitle file name adds to the name of its video: `cs` for `Film.cs.srt`. */
    fun tag(videoName: String, subtitleName: String): String =
        VideoFiles.baseName(subtitleName).drop(VideoFiles.baseName(videoName).length).trim { it in TAG_SEPARATORS }

    /** The language a [tag] starts with (`cs`, `eng`), if it looks like a language code. */
    fun language(tag: String): String? =
        tag.split('.', '_', '-', ' ').firstOrNull()
            ?.takeIf { it.length in 2..3 && it.all(Char::isLetter) }
            ?.lowercase()

    /**
     * Decodes subtitle text. Files without a byte order mark that are not valid UTF-8 come from
     * the days of regional code pages and are read with [legacyCharset].
     */
    fun decode(bytes: ByteArray, legacyCharset: Charset): String {
        fun startsWith(vararg bom: Int) =
            bytes.size >= bom.size && bom.indices.all { bytes[it] == bom[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                String(bytes, legacyCharset)
            }
        }
    }

    /** The Windows code page that subtitles in the given language were usually saved in. */
    fun legacyCharset(language: String): Charset {
        val name = when (language.lowercase()) {
            "cs", "sk", "pl", "hu", "sl", "hr", "bs", "ro", "sq" -> "windows-1250"
            "ru", "uk", "bg", "be", "sr", "mk" -> "windows-1251"
            "el" -> "windows-1253"
            "tr" -> "windows-1254"
            else -> "windows-1252"
        }
        return try {
            Charset.forName(name)
        } catch (_: IllegalArgumentException) {
            Charsets.ISO_8859_1
        }
    }

    private const val TAG_SEPARATORS = "._- "
}
