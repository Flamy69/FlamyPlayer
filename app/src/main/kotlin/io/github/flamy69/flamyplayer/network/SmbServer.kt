package io.github.flamy69.flamyplayer.network

import io.github.flamy69.flamyplayer.data.VideoFiles
import java.io.IOException

/** A saved network location: a Windows share, a NAS, or a folder somewhere inside one. */
data class SmbServer(
    val id: String,
    /** Label chosen by the user; blank means "show the address". */
    val name: String,
    /** Host name or IP address. */
    val host: String,
    /** Share and folders the location starts in. Empty means the list of shares of the host. */
    val root: List<String>,
    /** `user` or `DOMAIN\user`; blank signs in as a guest. */
    val user: String,
    /** The password, encrypted by [SecretStore]; blank if there is none. */
    val secret: String,
) {
    val address: String get() = (listOf(host) + root).joinToString("/")

    val title: String get() = name.ifBlank { address }
}

/**
 * One line of a directory listing.
 *
 * @param parts for a film that is spread over several files: those files, in playing order
 */
class SmbEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val parts: List<SmbEntry> = emptyList(),
) {
    /** Whether this line is something to watch. */
    val isVideo: Boolean get() = !isDirectory && (parts.isNotEmpty() || VideoFiles.isVideo(name))
}

object SmbAddress {

    private val hostPattern = Regex("[A-Za-z0-9._-]+")

    /**
     * Understands the ways people write a network location: `192.168.1.10`, `nas/video/films`,
     * `\\PC\Share` and `smb://nas/video`. Returns the host and the path below it, or `null` if
     * there is no usable host name.
     */
    fun parse(input: String): Pair<String, List<String>>? {
        val segments = input.trim()
            .replace('\\', '/')
            .replaceFirst(Regex("^smb:", RegexOption.IGNORE_CASE), "")
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val host = segments.firstOrNull()?.takeIf { hostPattern.matches(it) } ?: return null
        return host to segments.drop(1)
    }

    /** Splits `DOMAIN\user` (or `DOMAIN/user`) into domain and user name. */
    fun splitUser(user: String): Pair<String?, String> {
        val trimmed = user.trim()
        val separator = trimmed.indexOfFirst { it == '\\' || it == '/' }
        return if (separator < 0) {
            null to trimmed
        } else {
            trimmed.substring(0, separator).ifEmpty { null } to trimmed.substring(separator + 1)
        }
    }
}

/** Why a network operation failed, in terms the user can act on. */
class SmbException(val reason: Reason, cause: Throwable? = null) :
    IOException("SMB: $reason" + (cause?.message?.let { " ($it)" } ?: ""), cause) {

    enum class Reason {
        /** Nothing on the network answers to the name that was entered. */
        UNKNOWN_HOST,

        /** The machine is known, but does not accept the connection. */
        UNREACHABLE,
        LOGIN_FAILED,
        ACCESS_DENIED,
        NOT_FOUND,
        OTHER,
    }

    /** The underlying technical error in one line, for telling apart causes that look alike. */
    val detail: String
        get() {
            val root = generateSequence(cause) { it.cause }.take(8).lastOrNull() ?: return reason.name
            return listOfNotNull(root.javaClass.simpleName.ifEmpty { null }, root.message).joinToString(": ")
        }
}
