package net.swiftzer.metroride.tools.lowlmcfare

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import okio.Path.Companion.toPath

const val DEFAULT_STATIONS_URL = "https://opendata.mtr.com.hk/data/mtr_lines_and_stations.csv"

class FareExtractorCommand(
    private val pipeline: FarePipelineRunner = FarePipeline(),
) : CliktCommand(name = "mtr-low-lmc-cts-fare-table-extractor") {
    private val pdf by option("--pdf", help = "Local PDF path or HTTPS URL").required()
    private val page by option("--page", help = "1-based PDF page number")
        .int()
        .required()
        .validate { require(it >= 1) { "must be at least 1" } }
    private val output by option("--output", help = "Destination UTF-8 CSV path").required()
    private val stations by option(
        "--stations",
        help = "Local station CSV path or HTTPS URL",
    ).default(DEFAULT_STATIONS_URL)
    private val force by option("--force", help = "Replace an existing output file").flag()

    override fun run() {
        try {
            val count = pipeline.run(
                pdfInput = pdf,
                pageNumber = page,
                stationsInput = stations,
                outputPath = output.toPath(normalize = true),
                force = force,
            )
            echo("Wrote $count fare records to $output")
        } catch (failure: Exception) {
            throw PrintMessage(
                failure.message ?: failure::class.simpleName.orEmpty(),
                statusCode = 1,
                printError = true,
            )
        }
    }
}

fun main(args: Array<String>) = FareExtractorCommand().main(args)
