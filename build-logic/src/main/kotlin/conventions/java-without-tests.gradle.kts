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

// Each project sets its own archive base name from its own path; not configured from a build root.
base.archivesName.convention(project.path.removePrefix(":").replace(":", "-"))
