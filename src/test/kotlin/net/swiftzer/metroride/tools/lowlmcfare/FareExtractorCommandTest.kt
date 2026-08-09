package net.swiftzer.metroride.tools.lowlmcfare

import com.github.ajalt.clikt.testing.test
import okio.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FareExtractorCommandTest {
    @Test
    fun `requires the declared options`() {
        val result = FareExtractorCommand(FakePipeline()).test(emptyList())

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("--pdf"))
        assertTrue(result.stderr.contains("--page"))
        assertTrue(result.stderr.contains("--output"))
    }

    @Test
    fun `passes parsed values to pipeline`() {
        val pipeline = FakePipeline()
        val result = FareExtractorCommand(pipeline).test(
            "--pdf input.pdf --page 79 --stations stations.csv --output output.csv --force",
        )

        assertEquals(0, result.statusCode)
        assertEquals("input.pdf", pipeline.pdf)
        assertEquals(79, pipeline.page)
        assertEquals("stations.csv", pipeline.stations)
        assertTrue(pipeline.force)
        assertTrue(result.stdout.contains("Wrote 96 fare records"))
    }

    private class FakePipeline : FarePipelineRunner {
        var pdf = ""
        var page = 0
        var stations = ""
        var force = false

        override fun run(
            pdfInput: String,
            pageNumber: Int,
            stationsInput: String,
            outputPath: Path,
            force: Boolean,
        ): Int {
            pdf = pdfInput
            page = pageNumber
            stations = stationsInput
            this.force = force
            return 96
        }
    }
}
