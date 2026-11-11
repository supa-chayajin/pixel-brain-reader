package cloud.wafflecommons.pixelbrainreader.data.remote

/**
 * A failure worth another attempt: the connection dropped, timed out, or the network was
 * briefly gone. Never a refused token or a repository that doesn't exist.
 */
internal fun isTransientNetworkError(error: Throwable): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        val message = cause.message.orEmpty()
        if (message.contains("not authorized", ignoreCase = true) ||
            message.contains("authentication", ignoreCase = true) ||
            message.contains("not found", ignoreCase = true)
        ) return false
        if (cause is java.net.SocketException ||
            cause is java.io.InterruptedIOException ||
            cause is java.net.UnknownHostException ||
            cause is javax.net.ssl.SSLException ||
            cause is java.io.EOFException
        ) return true
        if (message.contains("connection reset", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) ||
            message.contains("broken pipe", ignoreCase = true) ||
            message.contains("premature eof", ignoreCase = true)
        ) return true
        cause = cause.cause
    }
    return false
}
