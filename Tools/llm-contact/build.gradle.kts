plugins {
    application
}

group = "io.github.strattonshrugged"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("info.picocli:picocli:4.7.6")
    implementation("io.github.cdimascio:dotenv-java:3.0.2")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.github.strattonshrugged.aieval.Cli"
    applicationName = "aieval"
}

// Lets the interactive picker read from the terminal under `gradlew run`.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}
