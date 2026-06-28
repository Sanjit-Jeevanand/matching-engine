plugins {
    id("java")
    id("application")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.exchange.matching.Application"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
