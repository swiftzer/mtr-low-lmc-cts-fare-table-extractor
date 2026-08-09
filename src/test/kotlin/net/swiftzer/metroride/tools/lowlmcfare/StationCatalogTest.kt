package net.swiftzer.metroride.tools.lowlmcfare

import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StationCatalogTest {
    @Test
    fun `accepts a real UTF-8 BOM before a quoted first header`() {
        val source = Buffer()
            .write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            .writeUtf8("\"Line Code\",\"Station ID\",\"English Name\"\r\nEAL,76,Lo Wu\r\n")

        assertEquals(76, StationCatalog.parse(source).resolve("Lo Wu").id)
    }

    @Test
    fun `parses normalized headers and resolves normalized names`() {
        val source = Buffer().writeUtf8(
            "\uFEFF LINE   CODE , station ID , ENGLISH NAME\r\n" +
                "EAL,87,Lo Wu\r\n" +
                "EAL,88,Lok Ma Chau\r\n",
        )

        val catalog = StationCatalog.parse(source)

        assertEquals(StationCatalog.Station("Lo Wu", 87), catalog.resolve("  LO　WU "))
        assertEquals(StationCatalog.Station("Lok Ma Chau", 88), catalog.resolve("lok ma chau"))
    }

    @Test
    fun `prefers non airport express entry`() {
        val catalog = StationCatalog.parse(
            Buffer().writeUtf8(
                "Line Code,Station ID,English Name\n" +
                    "AEL,1,Hong Kong\n" +
                    "TCL,2,Hong Kong\n" +
                    "ISL,2,Hong Kong\n",
            ),
        )

        assertEquals(2, catalog.resolve("Hong Kong").id)
    }

    @Test
    fun `allows station present on multiple lines with same id`() {
        val catalog = StationCatalog.parse(
            Buffer().writeUtf8(
                "Line Code,Station ID,English Name\n" +
                    "TWL,10,Central\n" +
                    "ISL,10,Central\n",
            ),
        )

        assertEquals(10, catalog.resolve("Central").id)
    }

    @Test
    fun `rejects ambiguous non airport express ids`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            StationCatalog.parse(
                Buffer().writeUtf8(
                    "Line Code,Station ID,English Name\n" +
                        "TWL,10,Central\n" +
                        "ISL,11,Central\n",
                ),
            )
        }

        assertTrue(error.message!!.contains("Ambiguous station ID"))
    }

    @Test
    fun `rejects missing headers and malformed station ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            StationCatalog.parse(Buffer().writeUtf8("Line Code,Station ID\nEAL,87\n"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StationCatalog.parse(
                Buffer().writeUtf8("Line Code,Station ID,English Name\nEAL,LOW,Lo Wu\n"),
            )
        }
    }

    @Test
    fun `rejects an unknown station`() {
        val catalog = StationCatalog.parse(
            Buffer().writeUtf8("Line Code,Station ID,English Name\nEAL,87,Lo Wu\n"),
        )

        assertThrows(IllegalArgumentException::class.java) { catalog.resolve("Missing") }
    }

    @Test
    fun `supplements racecourse omitted by the public feed`() {
        val catalog = StationCatalog.parse(
            Buffer().writeUtf8("Line Code,Station ID,English Name\nEAL,76,Lo Wu\n"),
        )

        assertEquals(StationCatalog.Station("Racecourse", 70), catalog.resolve("Racecourse"))
    }
}
