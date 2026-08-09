package net.swiftzer.metroride.tools.lowlmcfare

import okio.BufferedSink
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.RoundingMode

data class FareRecord(
    val stationName: String,
    val stationId: Int,
    val adult: BigDecimal,
    val adultFirstClass: BigDecimal,
    val child: BigDecimal,
    val childFirstClass: BigDecimal,
    val student: BigDecimal,
    val studentFirstClass: BigDecimal,
)

object CsvOutput {
    private val headers = arrayOf(
        "stationName",
        "stationId",
        "adult",
        "adultFirstClass",
        "child",
        "childFirstClass",
        "student",
        "studentFirstClass",
    )

    /** Writes a complete RFC 4180 CSV document without taking ownership of [sink]. */
    fun write(records: Iterable<FareRecord>, sink: BufferedSink) {
        val writer = OutputStreamWriter(sink.outputStream(), Charsets.UTF_8)
        val format = CSVFormat.RFC4180.builder()
            .setHeader(*headers)
            .get()
        val printer = CSVPrinter(writer, format)
        for (record in records) {
            printer.printRecord(
                record.stationName,
                record.stationId,
                record.adult.asFare(),
                record.adultFirstClass.asFare(),
                record.child.asFare(),
                record.childFirstClass.asFare(),
                record.student.asFare(),
                record.studentFirstClass.asFare(),
            )
        }
        printer.flush()
        sink.flush()
    }

    private fun BigDecimal.asFare(): String =
        setScale(1, RoundingMode.UNNECESSARY).toPlainString()
}
