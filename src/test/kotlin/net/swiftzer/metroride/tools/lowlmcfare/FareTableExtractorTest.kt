package net.swiftzer.metroride.tools.lowlmcfare

import java.nio.file.Path
import okio.Path.Companion.toPath
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FareTableExtractorTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `extracts both panels in visual order`() {
        val pdf = temporaryDirectory.resolve("table.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(600f, 800f))
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                writeRow(content, 40f, 700f, "Kennedy Town", "28.1 (54.0)", "13.4 (25.5)", "20.7 (46.6)")
                writeRow(content, 40f, 688f, "HKU", "28.1 (54.0)", "13.4 (25.5)", "20.7 (46.6)")
                writeRow(content, 330f, 700f, "North Point", "28.1 (54.0)", "13.4 (25.5)", "20.7 (46.6)")
            }
            document.save(pdf.toFile())
        }

        val rows = FareTableExtractor().extract(pdf.toString().toPath(), 1)

        assertEquals(listOf("Kennedy Town", "HKU", "North Point"), rows.map { it.stationName })
        assertEquals("54.0", rows.first().adult.firstClass.toPlainString())
        assertEquals("13.4", rows.first().child.ordinary.toPlainString())
    }

    private fun writeRow(
        content: PDPageContentStream,
        x: Float,
        y: Float,
        station: String,
        adult: String,
        child: String,
        student: String,
    ) {
        writeText(content, x, y, station)
        writeText(content, x + 100f, y, adult)
        writeText(content, x + 155f, y, child)
        writeText(content, x + 210f, y, student)
    }

    private fun writeText(content: PDPageContentStream, x: Float, y: Float, text: String) {
        content.beginText()
        content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 8f)
        content.newLineAtOffset(x, y)
        content.showText(text)
        content.endText()
    }
}
