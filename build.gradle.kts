plugins {
    java
    application
}

repositories { mavenCentral() }

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.code.gson:gson:2.11.0")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

application {
    mainClass = "eeg.Main"
}

tasks.test { useJUnitPlatform() }