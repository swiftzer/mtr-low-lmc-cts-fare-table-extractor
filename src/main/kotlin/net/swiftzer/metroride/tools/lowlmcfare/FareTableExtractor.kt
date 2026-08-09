package net.swiftzer.metroride.tools.lowlmcfare

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File
import java.io.Writer
import java.math.BigDecimal
import kotlin.math.abs
import okio.Path

data class FarePair(
    val ordinary: BigDecimal,
    val firstClass: BigDecimal,
)

data class ExtractedFareRow(
    val stationName: String,
    val adult: FarePair,
    val child: FarePair,
    val student: FarePair,
    val panel: Int,
    val baseline: Float,
)

class FareExtractionException(message: String) : IllegalArgumentException(message)

class FareTableExtractor {
    fun extract(pdfPath: Path, pageNumber: Int): List<ExtractedFareRow> {
        require(pageNumber >= 1) { "Page number must be at least 1" }

        Loader.loadPDF(File(pdfPath.toString())).use { document ->
            if (pageNumber > document.numberOfPages) {
                throw FareExtractionException(
                    "Page $pageNumber is outside the PDF (page count: ${document.numberOfPages})",
                )
            }

            val page = document.getPage(pageNumber - 1)
            val collector = GlyphCollector(pageNumber)
            collector.writeText(document, Writer.nullWriter())
            if (collector.glyphs.isEmpty()) {
                throw FareExtractionException("Page $pageNumber has no extractable text layer")
            }

            val pageWidth = page.cropBox.width
            return listOf(0, 1).flatMap { panel ->
                val glyphs = collector.glyphs.filter { glyph ->
                    if (panel == 0) glyph.centerX < pageWidth / 2f else glyph.centerX >= pageWidth / 2f
                }
                parsePanel(glyphs, panel, pageWidth / 2f)
            }.also { rows ->
                if (rows.isEmpty()) {
                    throw FareExtractionException(
                        "Page $pageNumber does not contain any complete Lo Wu / Lok Ma Chau fare rows",
                    )
                }
            }
        }
    }

    private fun parsePanel(glyphs: List<Glyph>, panel: Int, panelWidth: Float): List<ExtractedFareRow> {
        val lines = clusterLines(glyphs)
        val parsed = lines.mapNotNull { line -> parseCandidateLine(line, panel) }
        if (parsed.isEmpty()) return emptyList()

        val columnCenters = (0..2).map { index -> parsed.map { it.pairs[index].centerX }.median() }
        val tolerance = maxOf(5f, panelWidth * 0.035f)
        parsed.forEach { row ->
            row.pairs.forEachIndexed { index, pair ->
                if (abs(pair.centerX - columnCenters[index]) > tolerance) {
                    throw FareExtractionException(
                        "Ambiguous fare column near y=${"%.2f".format(row.baseline)} in panel ${panel + 1}: ${row.text}",
                    )
                }
            }
        }

        return parsed.sortedBy { it.baseline }.map { row ->
            ExtractedFareRow(
                stationName = row.stationName,
                adult = row.pairs[0].fare,
                child = row.pairs[1].fare,
                student = row.pairs[2].fare,
                panel = panel,
                baseline = row.baseline,
            )
        }
    }

    private fun clusterLines(glyphs: List<Glyph>): List<List<Glyph>> {
        val horizontal = glyphs.filter { glyph ->
            val direction = ((glyph.direction % 360f) + 360f) % 360f
            direction < 1f || abs(direction - 180f) < 1f || abs(direction - 360f) < 1f
        }.sortedWith(compareBy<Glyph> { it.y }.thenBy { it.x })

        val lines = mutableListOf<MutableList<Glyph>>()
        val baselines = mutableListOf<Float>()
        for (glyph in horizontal) {
            val index = baselines.indices.minByOrNull { abs(baselines[it] - glyph.y) }
            if (index != null && abs(baselines[index] - glyph.y) <= BASELINE_TOLERANCE) {
                lines[index] += glyph
                baselines[index] = lines[index].map { it.y }.average().toFloat()
            } else {
                lines += mutableListOf(glyph)
                baselines += glyph.y
            }
        }
        return lines.sortedBy { line -> line.map { it.y }.average() }
    }

    private fun parseCandidateLine(line: List<Glyph>, panel: Int): ParsedRow? {
        val reconstructed = reconstruct(line)
        val matches = FARE_PAIR_REGEX.findAll(reconstructed.text).toList()
        if (matches.isEmpty()) return null
        if (matches.size != 3) {
            throw FareExtractionException(
                "Expected 3 fare pairs near y=${"%.2f".format(line.map { it.y }.average())} " +
                    "in panel ${panel + 1}, found ${matches.size}: ${reconstructed.text}",
            )
        }

        val pairs = matches.map { match ->
            val matchedGlyphs = reconstructed.glyphs
                .subList(match.range.first, match.range.last + 1)
                .filterNotNull()
            if (matchedGlyphs.isEmpty()) {
                throw FareExtractionException("Unable to locate fare pair coordinates: ${match.value}")
            }
            LocatedFarePair(
                fare = FarePair(
                    ordinary = match.groupValues[1].toBigDecimal(),
                    firstClass = match.groupValues[2].toBigDecimal(),
                ),
                centerX = (matchedGlyphs.minOf { it.x } + matchedGlyphs.maxOf { it.right }) / 2f,
            )
        }.sortedBy { it.centerX }

        val firstFareX = pairs.first().centerX
        val stationText = reconstruct(line.filter { it.centerX < firstFareX - 8f }).text
        val stationName = ENGLISH_NAME_REGEX.findAll(stationText)
            .map { it.value.trim().trimEnd('*', '#').trim() }
            .filter { candidate -> candidate.count(Char::isLetter) >= 2 }
            .maxByOrNull { candidate -> candidate.count(Char::isLetter) }
            ?: throw FareExtractionException(
                "Unable to find an English station name near y=${"%.2f".format(line.map { it.y }.average())}: ${reconstructed.text}",
            )

        return ParsedRow(
            stationName = stationName,
            pairs = pairs,
            baseline = line.map { it.y }.average().toFloat(),
            text = reconstructed.text,
        )
    }

    private fun reconstruct(glyphs: List<Glyph>): ReconstructedText {
        val sorted = glyphs.sortedBy { it.x }
        val text = StringBuilder()
        val positions = mutableListOf<Glyph?>()
        var previous: Glyph? = null
        for (glyph in sorted) {
            val prior = previous
            if (prior != null) {
                val gap = glyph.x - prior.right
                val spaceThreshold = maxOf(0.7f, minOf(prior.height, glyph.height) * 0.13f)
                if (gap > spaceThreshold) {
                    text.append(' ')
                    positions += null
                }
            }
            glyph.text.forEach { character ->
                text.append(character)
                positions += glyph
            }
            previous = glyph
        }
        return ReconstructedText(text.toString(), positions)
    }

    private data class ParsedRow(
        val stationName: String,
        val pairs: List<LocatedFarePair>,
        val baseline: Float,
        val text: String,
    )

    private data class LocatedFarePair(val fare: FarePair, val centerX: Float)
    private data class ReconstructedText(val text: String, val glyphs: List<Glyph?>)

    private data class Glyph(
        val text: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val direction: Float,
    ) {
        val right: Float get() = x + width
        val centerX: Float get() = x + width / 2f
    }

    private class GlyphCollector(pageNumber: Int) : PDFTextStripper() {
        val glyphs = mutableListOf<Glyph>()

        init {
            startPage = pageNumber
            endPage = pageNumber
            sortByPosition = true
            setShouldSeparateByBeads(false)
            suppressDuplicateOverlappingText = true
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            textPositions.forEach { position ->
                if (position.unicode.isNotBlank()) {
                    glyphs += Glyph(
                        text = position.unicode,
                        x = position.xDirAdj,
                        y = position.yDirAdj,
                        width = position.widthDirAdj,
                        height = position.heightDir,
                        direction = position.dir,
                    )
                }
            }
        }
    }

    private companion object {
        const val BASELINE_TOLERANCE = 1.8f
        val FARE_PAIR_REGEX = Regex("(\\d+(?:\\.\\d+)?)\\s*[（(]\\s*(\\d+(?:\\.\\d+)?)\\s*[)）]")
        val ENGLISH_NAME_REGEX = Regex("[A-Za-z]+(?:[ .'-]+[A-Za-z]+)*[#*]?")
    }
}

private fun List<Float>.median(): Float {
    require(isNotEmpty())
    val sorted = sorted()
    return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
    else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
}
