package net.swiftzer.metroride.tools.lowlmcfare

import java.io.IOException
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path as NioPath

class InputResolverTest {
    @TempDir
    lateinit var tempDir: NioPath

    private var server: MockWebServer? = null

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    @Test
    fun `local PDF is returned and is not deleted on close`() {
        val path = tempDir.resolve("sample.pdf").toString().toPath()
        FileSystem.SYSTEM.write(path) { writeUtf8("pdf") }

        val resolved = resolver().resolvePdf(path.toString())
        assertEquals(path, resolved.path)
        resolved.close()

        assertTrue(FileSystem.SYSTEM.exists(path))
    }

    @Test
    fun `remote PDF is downloaded to a temporary file and deleted on close`() {
        val webServer = startServer()
        webServer.enqueue(MockResponse.Builder().body("pdf-data").build())

        val resolved = resolver(allowHttp = true).resolvePdf(webServer.url("/fare.pdf").toString())
        assertEquals("pdf-data", FileSystem.SYSTEM.read(resolved.path) { readUtf8() })
        assertTrue(FileSystem.SYSTEM.exists(resolved.path))

        resolved.close()
        assertFalse(FileSystem.SYSTEM.exists(resolved.path))
    }

    @Test
    fun `station CSV can be read from local file`() {
        val path = tempDir.resolve("stations.csv").toString().toPath()
        val expected = "Line Code,Station ID,English Name\nEAL,LOW,Lo Wu\n".encodeToByteArray()
        FileSystem.SYSTEM.write(path) { write(expected) }

        assertArrayEquals(expected, resolver().readStations(path.toString()).toByteArray())
    }

    @Test
    fun `rejects plain HTTP`() {
        val failure = assertThrows(InputResolutionException::class.java) {
            resolver().readStations("http://example.test/stations.csv")
        }
        assertTrue(failure.message!!.contains("HTTPS"))
    }

    @Test
    fun `reports unsuccessful HTTP status`() {
        val webServer = startServer()
        webServer.enqueue(MockResponse.Builder().code(503).build())

        val failure = assertThrows(InputResolutionException::class.java) {
            resolver(allowHttp = true).readStations(webServer.url("/stations.csv").toString())
        }
        assertTrue(failure.message!!.contains("HTTP 503"))
    }

    @Test
    fun `follows bounded same-scheme redirects`() {
        val webServer = startServer()
        webServer.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/actual.csv").build())
        webServer.enqueue(MockResponse.Builder().body("station-data").build())

        val bytes = resolver(allowHttp = true).readStations(webServer.url("/redirect").toString())

        assertEquals("station-data", bytes.utf8())
        assertEquals("/redirect", webServer.takeRequest().url.encodedPath)
        assertEquals("/actual.csv", webServer.takeRequest().url.encodedPath)
    }

    @Test
    fun `rejects HTTPS redirect downgrade before following it`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "http://example.test/insecure.csv")
                .body("".toResponseBody())
                .build()
        }.build()

        val failure = assertThrows(InputResolutionException::class.java) {
            InputResolver(client, temporaryDirectory = tempDir.toString().toPath())
                .readStations("https://example.test/redirect")
        }
        assertTrue(failure.message!!.contains("non-HTTPS"))
    }

    @Test
    fun `rejects response larger than station limit and removes partial PDF`() {
        val webServer = startServer()
        webServer.enqueue(
            MockResponse.Builder()
                .body(Buffer().write(ByteArray(128)))
                .addHeader("Content-Length", InputResolver.STATIONS_MAX_BYTES + 1)
                .build(),
        )

        val failure = assertThrows(InputResolutionException::class.java) {
            resolver(allowHttp = true).readStations(webServer.url("/huge.csv").toString())
        }
        assertTrue(failure.message!!.contains("5 MiB"))
    }

    @Test
    fun `enforces station limit while streaming when content length is absent`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ByteArray((InputResolver.STATIONS_MAX_BYTES + 1).toInt()).toResponseBody())
                .build()
        }.build()

        assertThrows(InputResolutionException::class.java) {
            InputResolver(client, temporaryDirectory = tempDir.toString().toPath())
                .readStations("https://example.test/stations.csv")
        }
    }

    private fun startServer(): MockWebServer = MockWebServer().also {
        server = it
        it.start()
    }

    private fun resolver(allowHttp: Boolean = false): InputResolver = InputResolver(
        client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build(),
        temporaryDirectory = tempDir.toString().toPath(),
        allowHttpForTests = allowHttp,
    )
}
