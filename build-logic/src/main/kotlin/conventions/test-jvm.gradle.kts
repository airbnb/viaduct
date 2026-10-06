package conventions

plugins {
    java
}

val testJavaVersion = providers.gradleProperty("testJavaVersion").orNull

if (testJavaVersion != null) {
    tasks.withType<Test>().configureEach {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(testJavaVersion)
        }
    }
}
