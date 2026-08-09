package net.swiftzer.metroride.tools.lowlmcfare

import java.nio.file.Path
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("integration")
class RealPdfIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `2024 page 79 matches verified golden CSV`() {
        val output = temporaryDirectory.resolve("2024.csv").toString().toPath()
        val count = FarePipeline().run(
            source("LEGCO_2024_PDF", PDF_2024),
            79,
            source("MTR_STATIONS_CSV", DEFAULT_STATIONS_URL),
            output,
            false,
        )

        assertEquals(96, count)
        assertEquals(
            "700faf53501dec2c0afb60a949f7823429c68b4a1343b1b4310c2794b234b88a",
            sha256(output),
        )
    }

    @Test
    fun `2023 page 78 extracts all rows and known endpoints`() {
        val output = temporaryDirectory.resolve("2023.csv").toString().toPath()
        val count = FarePipeline().run(
            source("LEGCO_2023_PDF", PDF_2023),
            78,
            source("MTR_STATIONS_CSV", DEFAULT_STATIONS_URL),
            output,
            false,
        )
        val lines = FileSystem.SYSTEM.source(output).buffer().use { source ->
            source.readUtf8().lines().filter(String::isNotEmpty)
        }

        assertEquals(96, count)
        assertEquals(
            "Kennedy Town,83,27.3,52.4,13.0,24.7,20.1,45.2",
            lines[1],
        )
        assertEquals(
            "Wu Kai Sha,103,11.6,21.7,5.4,10.2,11.6,21.7",
            lines.last(),
        )
    }

    private fun sha256(path: okio.Path): String {
        return FileSystem.SYSTEM.source(path).buffer().use { source ->
            source.readByteString().sha256().hex()
        }
    }

    private fun source(environmentVariable: String, defaultUrl: String): String =
        System.getenv(environmentVariable)?.takeIf(String::isNotBlank) ?: defaultUrl

    private companion object {
        const val PDF_2024 = "https://www.legco.gov.hk/yr2024/chinese/panels/tp/papers/tpcb4-737-1-c.pdf"
        const val PDF_2023 = "https://www.legco.gov.hk/yr2023/chinese/panels/tp/papers/tpcb4-535-1-c.pdf"
    }
}
