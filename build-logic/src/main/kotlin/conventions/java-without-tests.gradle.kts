package conventions

import buildroot.registerForOrchestrationAggregate

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

// Self-report this project's lifecycle-base tasks for orchestration aggregation.
registerForOrchestrationAggregate("build", "build")
registerForOrchestrationAggregate("check", "check")
registerForOrchestrationAggregate("clean", "clean")
registerForOrchestrationAggregate("classes", "classes")
