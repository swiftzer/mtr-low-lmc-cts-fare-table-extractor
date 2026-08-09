package net.swiftzer.metroride.tools.lowlmcfare

import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import org.apache.commons.csv.CSVFormat
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.Locale

/** A lookup of official MTR station names and IDs. */
class StationCatalog private constructor(
    private val stationsByNormalizedName: Map<String, Station>,
) {
    data class Station(
        val name: String,
        val id: Int,
    )

    /** Resolves an English station name or throws when it is absent from the official data. */
    fun resolve(englishName: String): Station =
        stationsByNormalizedName[normalizeStationName(englishName)]
            ?: throw IllegalArgumentException("Unknown MTR station: '$englishName'")

    companion object {
        private const val LINE_CODE_HEADER = "line code"
        private const val STATION_ID_HEADER = "station id"
        private const val ENGLISH_NAME_HEADER = "english name"
        private val UTF8_BOM = "efbbbf".decodeHex()

        fun parse(source: BufferedSource): StationCatalog {
            if (source.rangeEquals(0, UTF8_BOM)) source.skip(UTF8_BOM.size.toLong())
            val reader = InputStreamReader(source.inputStream(), Charsets.UTF_8)
            val format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get()

            format.parse(reader).use { parser ->
                val headers = parser.headerNames.associateByUniqueNormalizedHeader()
                val lineHeader = headers.requiredHeader(LINE_CODE_HEADER)
                val stationIdHeader = headers.requiredHeader(STATION_ID_HEADER)
                val englishNameHeader = headers.requiredHeader(ENGLISH_NAME_HEADER)

                val candidates = linkedMapOf<String, MutableList<Candidate>>()
                for (record in parser) {
                    val name = record[englishNameHeader].trim()
                    val rawId = record[stationIdHeader].trim()
                    val lineCode = record[lineHeader].trim()
                    if (name.isEmpty() && rawId.isEmpty() && lineCode.isEmpty()) continue
                    require(name.isNotEmpty() && rawId.isNotEmpty() && lineCode.isNotEmpty()) {
                        "Incomplete station CSV record at line ${record.recordNumber + 1}"
                    }
                    val id = rawId.toIntOrNull()
                        ?: throw IllegalArgumentException(
                            "Invalid station ID '$rawId' at line ${record.recordNumber + 1}",
                        )
                    candidates.getOrPut(normalizeStationName(name)) { mutableListOf() }
                        .add(Candidate(name, id, lineCode))
                }

                // The public lines-and-stations feed omits Racecourse because it is only
                // served on race days, while the concession fare table always includes it.
                candidates.putIfAbsent(
                    normalizeStationName("Racecourse"),
                    mutableListOf(Candidate("Racecourse", 70, "EAL")),
                )

                require(candidates.isNotEmpty()) { "Station CSV contains no station records" }
                return StationCatalog(candidates.mapValues { (normalizedName, entries) ->
                    chooseStation(normalizedName, entries)
                })
            }
        }

        internal fun normalizeStationName(value: String): String = Normalizer
            .normalize(value, Normalizer.Form.NFKC)
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u2010', '-')
            .replace('\u2011', '-')
            .replace('\u2012', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)

        private fun List<String>.associateByUniqueNormalizedHeader(): Map<String, String> {
            val result = linkedMapOf<String, String>()
            for (original in this) {
                val normalized = normalizeHeader(original)
                require(normalized !in result) {
                    "Station CSV contains duplicate header '$normalized'"
                }
                result[normalized] = original
            }
            return result
        }

        private fun Map<String, String>.requiredHeader(name: String): String =
            this[name] ?: throw IllegalArgumentException("Station CSV is missing required header '$name'")

        private fun normalizeHeader(value: String): String = value
            .removePrefix("\uFEFF")
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)

        private fun chooseStation(normalizedName: String, entries: List<Candidate>): Station {
            val nonAirportExpress = entries.filterNot { it.lineCode.equals("AEL", ignoreCase = true) }
            val preferred = nonAirportExpress.ifEmpty { entries }
            val ids = preferred.map { it.id }.distinct()
            require(ids.size == 1) {
                "Ambiguous station ID for '${entries.first().name}' ($normalizedName): ${ids.joinToString()}"
            }
            val selected = preferred.first { it.id == ids.single() }
            return Station(selected.name, selected.id)
        }

        private data class Candidate(
            val name: String,
            val id: Int,
            val lineCode: String,
        )
    }
}
