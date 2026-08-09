# MTR Lo Wu / Lok Ma Chau Chau Concessionary Travel Scheme fare table extractor

A JVM 17 command-line tool that extracts the positioned text from a Lo Wu / Lok Ma Chau
Concessionary Travel Scheme fare-table page in an MTR PDF and writes the normalized fares as CSV.

## Build

```shell
./gradlew distZip
```

The distribution contains launchers for Windows and Unix under `build/distributions`.

## Usage

```shell
mtr-low-lmc-cts-fare-table-extractor \
  --pdf <local-pdf-path-or-https-url> \
  --page <1-based-page-number> \
  --output <csv-path> \
  [--stations <local-csv-path-or-https-url>] \
  [--force]
```

`--stations` defaults to the MTR lines-and-stations open-data feed. Existing output is not
replaced unless `--force` is present. The output columns are:

```text
stationName,stationId,adult,adultFirstClass,child,childFirstClass,student,studentFirstClass
```

The current public station feed omits Racecourse because it is only served on race days. The
extractor supplies the scheme's stable `Racecourse` station ID (`70`) when it is absent.

## Tests

The normal suite is offline:

```shell
./gradlew test
```

Real upstream documents are covered by JUnit tests tagged `integration`:

```shell
./gradlew integrationTest
```

For environments whose JVM trust store cannot reach the upstream HTTPS sites, download the
same public inputs separately and point the integration tests at them:

```shell
LEGCO_2024_PDF=/path/to/2024.pdf \
LEGCO_2023_PDF=/path/to/2023.pdf \
MTR_STATIONS_CSV=/path/to/mtr_lines_and_stations.csv \
./gradlew integrationTest
```

## License

Licensed under the [Apache License 2.0](LICENSE).
