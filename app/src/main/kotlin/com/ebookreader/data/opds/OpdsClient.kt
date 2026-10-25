package com.ebookreader.data.opds

import com.ebookreader.data.library.SourceKind
import com.ebookreader.data.library.copyWithProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Why a catalogue page or a download failed, in words for the reader. */
class CatalogException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A finished download: the file, and the name and type it should be imported as. */
data class DownloadedBook(val file: File, val fileName: String, val kind: SourceKind)

/**
 * Talks to OPDS catalogues. This is the only part of the app that goes online, and only
 * while the reader browses a catalogue; books themselves never reach the network.
 *
 * Every address is fetched over HTTPS: catalogues still hand out plain-http links (the
 * Gutenberg search does), and the platform refuses cleartext traffic.
 */
@Singleton
class OpdsClient @Inject constructor() {

    private val searchTemplates = mutableMapOf<String, String?>()

    suspend fun feed(url: String): OpdsFeed = withContext(Dispatchers.IO) {
        request(url, ACCEPT_FEED) { connection, finalUrl ->
            try {
                connection.inputStream.use { OpdsParser.parse(it, finalUrl) }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw CatalogException("This address is not an OPDS catalogue", e)
            }
        }
    }

    /** The search address for [feed]'s catalogue, or null when it cannot be searched. */
    suspend fun searchTemplate(feed: OpdsFeed): String? {
        feed.searchTemplate?.let { return it }
        val description = feed.searchDescription ?: return null
        searchTemplates[description]?.let { return it }
        val template = withContext(Dispatchers.IO) {
            runCatching {
                request(description, "application/opensearchdescription+xml, */*;q=0.5") { connection, finalUrl ->
                    connection.inputStream.use { OpdsParser.parseSearchTemplate(it, finalUrl) }
                }
            }.getOrNull()
        }
        searchTemplates[description] = template
        return template
    }

    /**
     * Downloads [link] into [dir]. The file is named after [title] with the extension of
     * its type, since catalogue addresses ("84.epub3.images") rarely end in one.
     */
    suspend fun download(link: OpdsLink, title: String, dir: File, onProgress: (Float) -> Unit): DownloadedBook =
        withContext(Dispatchers.IO) {
            request(link.href, "*/*") { connection, finalUrl ->
                // The catalogue's word for the format first; the server's if it gave none.
                val kind = link.kind ?: SourceKind.detect(URL(finalUrl).path.substringAfterLast('/'), connection.contentType)
                    ?: throw CatalogException("This app cannot open ${link.title ?: connection.contentType ?: "this format"}")
                val name = safeName(title) + "." + kind.extensions.first()
                val out = File(dir, "download_${System.nanoTime()}_$name")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: link.length ?: -1L
                try {
                    connection.inputStream.use { copyWithProgress(it, out, total, onProgress) }
                } catch (e: Throwable) {
                    // A dropped connection or a cancel leaves no half a book in the cache.
                    out.delete()
                    throw e
                }
                DownloadedBook(out, name, kind)
            }
        }

    /** Opens [url], following redirects (also from http to https), and hands over the response. */
    private suspend fun <T> request(url: String, accept: String, read: suspend (HttpURLConnection, String) -> T): T {
        var current = secure(url)
        repeat(MAX_REDIRECTS) {
            val connection = try {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", accept)
                }
            } catch (e: Exception) {
                throw CatalogException("That is not a web address", e)
            }
            try {
                val code = try {
                    connection.responseCode
                } catch (e: IOException) {
                    throw CatalogException("Can't reach the catalogue. Check your connection.", e)
                }
                when (code) {
                    in 200..299 -> return read(connection, current)
                    in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw CatalogException("The catalogue sent a broken redirect")
                        current = secure(URL(URL(current), location).toString())
                    }
                    401, 403 -> throw CatalogException("This catalogue needs an account, which this app can't sign in to yet")
                    404 -> throw CatalogException("Nothing at this address")
                    else -> throw CatalogException("The catalogue answered with error $code")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw CatalogException("Too many redirects")
    }

    private fun secure(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    private fun safeName(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ").replace(Regex("""\s+"""), " ").trim().take(80).ifEmpty { "book" }

    private companion object {
        const val MAX_REDIRECTS = 6
        const val TIMEOUT_MS = 20_000
        const val USER_AGENT = "EBookReader/1.0 (Android; OPDS)"
        const val ACCEPT_FEED = "application/atom+xml;profile=opds-catalog, application/atom+xml, application/xml;q=0.9, */*;q=0.5"
    }
}
