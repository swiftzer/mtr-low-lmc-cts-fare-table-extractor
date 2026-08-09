package net.swiftzer.metroride.tools.lowlmcfare

import java.io.Closeable
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import okio.ByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Source

/** Resolves local files and HTTPS resources without exposing network streams to callers. */
class InputResolver(
    client: OkHttpClient = defaultHttpClient(),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val temporaryDirectory: Path = System.getProperty("java.io.tmpdir").toPath(),
    internal val allowHttpForTests: Boolean = false,
) {
    private val httpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun resolvePdf(input: String): ResolvedFile {
        val remoteUrl = parseInput(input)
        if (remoteUrl == null) {
            val path = input.toPath(normalize = true)
            validateLocalFile(path, PDF_MAX_BYTES, "PDF")
            return ResolvedFile(path, fileSystem, deleteOnClose = false)
        }

        val temporaryFile = createTemporaryFile("mtr-fare-table-", ".pdf")
        try {
            openRemote(remoteUrl, PDF_MAX_BYTES, "PDF").use { response ->
                fileSystem.sink(temporaryFile, mustCreate = false).use { sink ->
                    copyBounded(response.body.source(), sink, PDF_MAX_BYTES, "PDF")
                }
            }
            return ResolvedFile(temporaryFile, fileSystem, deleteOnClose = true)
        } catch (failure: Throwable) {
            deleteIfPresent(temporaryFile)
            throw failure
        }
    }

    fun readStations(input: String): ByteString {
        val remoteUrl = parseInput(input)
        return if (remoteUrl == null) {
            val path = input.toPath(normalize = true)
            validateLocalFile(path, STATIONS_MAX_BYTES, "station CSV")
            fileSystem.source(path).use { source -> readBounded(source, STATIONS_MAX_BYTES, "station CSV") }
        } else {
            openRemote(remoteUrl, STATIONS_MAX_BYTES, "station CSV").use { response ->
                readBounded(response.body.source(), STATIONS_MAX_BYTES, "station CSV")
            }
        }
    }

    private fun parseInput(input: String): HttpUrl? {
        require(input.isNotBlank()) { "Input must not be blank" }
        val scheme = SCHEME_PREFIX.find(input)?.groupValues?.get(1)?.lowercase() ?: return null
        if (scheme != "https" && !(allowHttpForTests && scheme == "http")) {
            throw InputResolutionException("Only local paths and HTTPS URLs are supported: $input")
        }
        return input.toHttpUrlOrNull()
            ?: throw InputResolutionException("Invalid $scheme URL: $input")
    }

    private fun openRemote(initialUrl: HttpUrl, limit: Long, description: String): Response {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val response = try {
                httpClient.newCall(Request.Builder().url(url).get().build()).execute()
            } catch (failure: IOException) {
                throw InputResolutionException("Failed to download $description from $url", failure)
            }

            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                val redirectedUrl = location?.let(url::resolve)
                response.close()
                if (redirectedUrl == null) {
                    throw InputResolutionException("Redirect from $url has no valid Location header")
                }
                if (redirectedUrl.scheme != "https" && !(allowHttpForTests && redirectedUrl.scheme == "http")) {
                    throw InputResolutionException("Refusing redirect from $url to non-HTTPS URL $redirectedUrl")
                }
                if (redirectCount == MAX_REDIRECTS) {
                    throw InputResolutionException("Too many redirects while downloading $description")
                }
                url = redirectedUrl
                return@repeat
            }

            if (!response.isSuccessful) {
                val status = response.code
                response.close()
                throw InputResolutionException("Failed to download $description from $url: HTTP $status")
            }
            val contentLength = response.body.contentLength()
            if (contentLength > limit) {
                response.close()
                throw InputResolutionException("$description exceeds the ${formatLimit(limit)} limit")
            }
            return response
        }
        error("Redirect loop terminated unexpectedly")
    }

    private fun validateLocalFile(path: Path, limit: Long, description: String) {
        val metadata = try {
            fileSystem.metadata(path)
        } catch (failure: IOException) {
            throw InputResolutionException("Cannot read local $description file: $path", failure)
        }
        if (!metadata.isRegularFile) {
            throw InputResolutionException("Local $description input is not a regular file: $path")
        }
        if (metadata.size?.let { it > limit } == true) {
            throw InputResolutionException("$description exceeds the ${formatLimit(limit)} limit")
        }
    }

    private fun createTemporaryFile(prefix: String, suffix: String): Path {
        fileSystem.createDirectories(temporaryDirectory)
        repeat(10) {
            val candidate = temporaryDirectory / "$prefix${UUID.randomUUID()}$suffix"
            try {
                fileSystem.sink(candidate, mustCreate = true).close()
                return candidate
            } catch (_: IOException) {
                // An extremely unlikely collision; generate another name.
            }
        }
        throw InputResolutionException("Could not create a temporary file in $temporaryDirectory")
    }

    private fun deleteIfPresent(path: Path) {
        try {
            fileSystem.delete(path, mustExist = false)
        } catch (_: IOException) {
            // Preserve the original download error when cleanup also fails.
        }
    }

    companion object {
        const val PDF_MAX_BYTES: Long = 100L * 1024L * 1024L
        const val STATIONS_MAX_BYTES: Long = 5L * 1024L * 1024L
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        private val SCHEME_PREFIX = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

        private fun defaultHttpClient(): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(2, TimeUnit.MINUTES)

            // The JDK normally uses its bundled cacerts file. On Windows, use the OS trust
            // store so the CLI behaves like other native HTTPS clients without weakening TLS.
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                val trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm(),
                )
                val windowsRoots = KeyStore.getInstance("Windows-ROOT").apply { load(null, null) }
                trustManagerFactory.init(windowsRoots)
                val trustManager = trustManagerFactory.trustManagers
                    .filterIsInstance<X509TrustManager>()
                    .single()
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(trustManager), null)
                }
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            }
            return builder.build()
        }

        private fun copyBounded(source: Source, sink: okio.Sink, limit: Long, description: String) {
            val buffer = Buffer()
            var total = 0L
            while (true) {
                val read = source.read(buffer, minOf(8_192L, limit - total + 1L))
                if (read == -1L) break
                total += read
                if (total > limit) {
                    throw InputResolutionException("$description exceeds the ${formatLimit(limit)} limit")
                }
                sink.write(buffer, read)
            }
        }

        private fun readBounded(source: Source, limit: Long, description: String): ByteString {
            val buffer = Buffer()
            copyBounded(source, buffer, limit, description)
            return buffer.readByteString()
        }

        private fun formatLimit(limit: Long): String = "${limit / (1024L * 1024L)} MiB"
    }
}

class ResolvedFile internal constructor(
    val path: Path,
    private val fileSystem: FileSystem,
    private val deleteOnClose: Boolean,
) : Closeable {
    override fun close() {
        if (deleteOnClose) fileSystem.delete(path, mustExist = false)
    }
}

class InputResolutionException(message: String, cause: Throwable? = null) : IOException(message, cause)
