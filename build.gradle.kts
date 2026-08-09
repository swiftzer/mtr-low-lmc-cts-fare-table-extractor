plugins {
    kotlin("jvm") version "2.4.0"
    application
}

group = "net.swiftzer.metroride"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.squareup.okio:okio:3.16.4")
    implementation("org.apache.commons:commons-csv:1.14.1")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "net.swiftzer.metroride.tools.lowlmcfare.MainKt"
    applicationName = "mtr-low-lmc-cts-fare-table-extractor"
    applicationDistribution.from("LICENSE")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs network integration tests against real upstream files."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}
