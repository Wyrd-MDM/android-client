package link.wyrd.net

import java.net.URI

private const val HTTPS = "https"
private const val HTTPS_PORT = 443

fun onServer(url: String, serverUrl: String?): Boolean {
    val target = runCatching { URI(url) }.getOrNull() ?: return false
    val server = serverUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return false
    return target.scheme == HTTPS && server.scheme == HTTPS &&
        target.host != null && target.host.equals(server.host, ignoreCase = true) &&
        target.portOrDefault() == server.portOrDefault()
}

private fun URI.portOrDefault() = if (port == -1) HTTPS_PORT else port
