package buildroot

plugins {
    id("com.autonomousapps.dependency-analysis")
}

configure<com.autonomousapps.DependencyAnalysisExtension> {
    issues {
        all {
            onAny {
                severity("warn") // not linked to build or check, so might just as well print all issues instead of failing on the first ones
            }
        }
    }

    structure {
        bundle("kotlin") {
            includeGroup("org.jetbrains.kotlin")
        }
    }
}