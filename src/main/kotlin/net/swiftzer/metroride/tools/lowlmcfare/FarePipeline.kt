package net.swiftzer.metroride.tools.lowlmcfare

import java.math.BigDecimal
import java.util.UUID
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

fun interface FarePipelineRunner {
    fun run(
        pdfInput: String,
        pageNumber: Int,
        stationsInput: String,
        outputPath: Path,
        force: Boolean,
    ): Int
}

class FarePipeline(
    private val inputResolver: InputResolver = InputResolver(),
    private val extractor: FareTableExtractor = FareTableExtractor(),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : FarePipelineRunner {
    override fun run(
        pdfInput: String,
        pageNumber: Int,
        stationsInput: String,
        outputPath: Path,
        force: Boolean,
    ): Int {
        require(pageNumber >= 1) { "Page number must be at least 1" }
        if (fileSystem.exists(outputPath) && !force) {
            throw IllegalArgumentException("Output already exists; pass --force to replace it: $outputPath")
        }

        val catalog = Buffer().write(inputResolver.readStations(stationsInput)).use(StationCatalog::parse)
        val extracted = inputResolver.resolvePdf(pdfInput).use { pdf ->
            extractor.extract(pdf.path, pageNumber)
        }
        val records = resolveAndDeduplicate(extracted, catalog)
        publishAtomically(records, outputPath, force)
        return records.size
    }

    internal fun resolveAndDeduplicate(
        rows: List<ExtractedFareRow>,
        catalog: StationCatalog,
    ): List<FareRecord> {
        val byName = linkedMapOf<String, FareRecord>()
        val nameById = mutableMapOf<Int, String>()
        for (row in rows) {
            val station = catalog.resolve(row.stationName)
            val record = FareRecord(
                station.name, station.id,
                row.adult.ordinary, row.adult.firstClass,
                row.child.ordinary, row.child.firstClass,
                row.student.ordinary, row.student.firstClass,
            )
            val key = StationCatalog.normalizeStationName(station.name)
            val previous = byName[key]
            if (previous != null) {
                if (!previous.hasSameFares(record) || previous.stationId != record.stationId) {
                    throw FareExtractionException("Conflicting duplicate fare row for ${station.name}")
                }
                continue
            }
            val otherName = nameById[station.id]
            if (otherName != null && otherName != key) {
                throw FareExtractionException(
                    "Station ID ${station.id} resolves to both ${byName.getValue(otherName).stationName} and ${station.name}",
                )
            }
            byName[key] = record
            nameById[station.id] = key
        }
        if (byName.isEmpty()) throw FareExtractionException("No fare records were produced")
        return byName.values.toList()
    }

    private fun publishAtomically(records: List<FareRecord>, outputPath: Path, force: Boolean) {
        val parent = outputPath.parent ?: ".".toPath()
        fileSystem.createDirectories(parent)
        val temporary = parent / ".${outputPath.name}.tmp-${UUID.randomUUID()}"
        try {
            fileSystem.sink(temporary, mustCreate = true).buffer().use { CsvOutput.write(records, it) }
            if (force) fileSystem.delete(outputPath, mustExist = false)
            fileSystem.atomicMove(temporary, outputPath)
        } finally {
            fileSystem.delete(temporary, mustExist = false)
        }
    }
}

private fun FareRecord.hasSameFares(other: FareRecord): Boolean =
    listOf<BigDecimal>(adult, adultFirstClass, child, childFirstClass, student, studentFirstClass) ==
        listOf(other.adult, other.adultFirstClass, other.child, other.childFirstClass, other.student, other.studentFirstClass)
