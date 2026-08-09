package net.swiftzer.metroride.tools.lowlmcfare

import java.math.BigDecimal
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FarePipelineTest {
    private val catalog = StationCatalog.parse(Buffer().writeUtf8(
        "Line Code,Station ID,English Name\r\n" +
            "ISL,83,Kennedy Town\r\n" +
            "EAL,31,North Point\r\n",
    ))

    @Test
    fun `deduplicates equal rows while preserving first order`() {
        val row = extracted("Kennedy Town", "28.1", "54.0")
        val records = FarePipeline().resolveAndDeduplicate(
            listOf(row, extracted("North Point", "20.0", "40.0"), row),
            catalog,
        )

        assertEquals(listOf("Kennedy Town", "North Point"), records.map { it.stationName })
    }

    @Test
    fun `rejects conflicting duplicate fares`() {
        assertThrows(FareExtractionException::class.java) {
            FarePipeline().resolveAndDeduplicate(
                listOf(
                    extracted("Kennedy Town", "28.1", "54.0"),
                    extracted("Kennedy Town", "29.1", "54.0"),
                ),
                catalog,
            )
        }
    }

    private fun extracted(name: String, adult: String, first: String) = ExtractedFareRow(
        stationName = name,
        adult = FarePair(BigDecimal(adult), BigDecimal(first)),
        child = FarePair(BigDecimal("10.0"), BigDecimal("20.0")),
        student = FarePair(BigDecimal("15.0"), BigDecimal("30.0")),
        panel = 0,
        baseline = 100f,
    )
}
