package net.swiftzer.metroride.tools.lowlmcfare

import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CsvOutputTest {
    @Test
    fun `writes fixed schema RFC 4180 csv with one decimal fares`() {
        val sink = Buffer()
        CsvOutput.write(
            listOf(
                FareRecord(
                    stationName = "Station, \"Quoted\"",
                    stationId = 12,
                    adult = BigDecimal("54"),
                    adultFirstClass = BigDecimal("108.0"),
                    child = BigDecimal("27.00"),
                    childFirstClass = BigDecimal("54.0"),
                    student = BigDecimal("54.0"),
                    studentFirstClass = BigDecimal("108.0"),
                ),
            ),
            sink,
        )

        assertEquals(
            "stationName,stationId,adult,adultFirstClass,child,childFirstClass,student,studentFirstClass\r\n" +
                "\"Station, \"\"Quoted\"\"\",12,54.0,108.0,27.0,54.0,54.0,108.0\r\n",
            sink.readUtf8(),
        )
    }

    @Test
    fun `writes header for empty output`() {
        val sink = Buffer()

        CsvOutput.write(emptyList(), sink)

        assertEquals(
            "stationName,stationId,adult,adultFirstClass,child,childFirstClass,student,studentFirstClass\r\n",
            sink.readUtf8(),
        )
    }

    @Test
    fun `rejects a fare that cannot be represented with one decimal`() {
        val sink = Buffer()
        val record = FareRecord(
            "Lo Wu",
            87,
            BigDecimal("1.25"),
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
        )

        assertThrows(ArithmeticException::class.java) { CsvOutput.write(listOf(record), sink) }
    }
}
