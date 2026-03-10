import java.net.URL

plugins {
    `java-base`  // for Java toolchain support
}

val copybaraJar = layout.projectDirectory.file("copybara-cache/copybara_deploy.jar").asFile

val downloadCopybara by tasks.registering {
    group = "copybara"
    description = "Downloads Copybara JAR from GitHub releases"
    outputs.file(copybaraJar)
    onlyIf { !copybaraJar.exists() }
    notCompatibleWithConfigurationCache("Downloads external JAR at runtime")
    doLast {
        copybaraJar.parentFile.mkdirs()
        val apiUrl = "https://api.github.com/repos/google/copybara/releases/latest"
        val connection = URL(apiUrl).openConnection()
        connection.setRequestProperty("Accept", "application/json")
        val response = connection.getInputStream().bufferedReader().readText()
        val pattern = """"browser_download_url":\s*"([^"]*copybara_deploy\.jar)"""".toRegex()
        val downloadUrl = pattern.find(response)?.groupValues?.get(1)
            ?: throw GradleException("Could not find copybara_deploy.jar in GitHub releases")
        logger.lifecycle("Downloading Copybara from {}", downloadUrl)
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
        .map { if (it.isNotEmpty()) it.split(" ") else emptyList() }
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
