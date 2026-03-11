import java.net.URL

plugins {
    `java-base`  // for Java toolchain support
}

// We intentionally download the latest Copybara release rather than pinning
// to a specific version. Copybara has no stable release track — all releases
// are automated weekly snapshots from master, with "version compatibility or
// correctness not guaranteed" per the release notes. Given that this tooling
// is an obscure corner of Viaduct that sees infrequent attention, pinning
// would mean silently falling years behind; we prefer the small risk of an
// occasional breakage from an automatic upgrade over the certainty of
// accumulating a painful amount of drift. The jar is cached in build/ and
// refreshed whenever the build directory is cleaned.
val copybaraJar = layout.buildDirectory.file("copybara_deploy.jar").get().asFile

val downloadCopybara by tasks.registering {
    group = "copybara"
    description = "Downloads the latest Copybara JAR from GitHub releases"
    outputs.file(copybaraJar)
    onlyIf { !copybaraJar.exists() }
    notCompatibleWithConfigurationCache("Downloads external JAR at runtime")
    doLast {
        copybaraJar.parentFile.mkdirs()
        val downloadUrl = "https://github.com/google/copybara/releases/latest/download/copybara_deploy.jar"
        logger.lifecycle("Downloading latest Copybara from {}", downloadUrl)
        URL(downloadUrl).openStream().use { it.copyTo(copybaraJar.outputStream()) }
        logger.lifecycle("Downloaded to {}", copybaraJar)
    }
}

tasks.register<JavaExec>("runCopybara") {
    group = "copybara"
    description = "Runs Copybara (https://github.com/google/copybara)"
    dependsOn(downloadCopybara)

    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })

    // Default to repo root (two levels up from .github/copydemoapps/),
    // overridable via -PrepoRoot=/path/to/repo
    workingDir = providers.gradleProperty("repoRoot")
        .map { file(it) }
        .getOrElse(projectDir.parentFile.parentFile)

    classpath = files(copybaraJar)
    mainClass.set("com.google.copybara.Main")

    args = providers.gradleProperty("copybaraArgs")
        .map { if (it.isNotEmpty()) it.split("\u001F") else emptyList() }
        .getOrElse(emptyList())

    notCompatibleWithConfigurationCache("Copybara execution is not compatible with configuration cache")
    isIgnoreExitValue = true
    doLast {
        val exitValue = executionResult.get().exitValue
        // Exit code 4 means NO_OP (nothing to sync) — treat as success
        if (exitValue != 0 && exitValue != 4) {
            throw GradleException("Copybara failed with exit code $exitValue")
        }
    }
}
