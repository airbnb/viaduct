package conventions

plugins {
    idea
    java
    id("conventions.java-static-analysis")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
