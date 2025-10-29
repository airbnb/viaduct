import java.net.URL
import java.io.InputStream

// Copybara configuration
val copybaraJarDir = file("$projectDir/copybara")
val copybaraJar = file("$copybaraJarDir/copybara_deploy.jar")

// Configuration for copybara classpath
configurations {
    create("copybara")
}

// Task to download Copybara JAR
val downloadCopybara by tasks.registering {
    group = "copybara"
    description = "Downloads Copybara JAR from GitHub releases"

    outputs.file(copybaraJar)

    // Disable configuration cache for this task
    notCompatibleWithConfigurationCache("Downloads external JAR at runtime")

    doLast {
        copybaraJarDir.mkdirs()

        // Get latest release URL from GitHub API
        val apiUrl = "https://api.github.com/repos/google/copybara/releases/latest"
        val connection = URL(apiUrl).openConnection()
        connection.setRequestProperty("Accept", "application/json")
        val response = connection.getInputStream().bufferedReader().readText()

        // Parse JSON to find download URL for copybara_deploy.jar
        // Look for: "browser_download_url": "https://...copybara_deploy.jar"
        val pattern = """"browser_download_url":\s*"([^"]*copybara_deploy\.jar)"""".toRegex()
        val downloadUrl = pattern.find(response)?.groupValues?.get(1)
            ?: throw GradleException("Could not find copybara_deploy.jar download URL in GitHub releases")

        logger.lifecycle("Downloading Copybara from {}", downloadUrl)

        // Download the JAR
        val url = URL(downloadUrl)
        url.openStream().use { input: InputStream ->
            copybaraJar.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        logger.lifecycle("Copybara downloaded to ${copybaraJar.absolutePath}")
    }
}

// Add the downloaded JAR to the copybara configuration
dependencies {
    add("copybara", files(copybaraJar))
}

// Ensure the JAR is downloaded before using it
configurations.named("copybara") {
    val downloadTask = tasks.named("downloadCopybara")

    // Only download if the JAR doesn't exist
    incoming.beforeResolve {
        if (!copybaraJar.exists()) {
            downloadTask.get().actions.forEach { it.execute(downloadTask.get()) }
        }
    }
}

// Copybara execution task
tasks.register<JavaExec>("runCopybara") {
    group = "copybara"
    description = "Runs Copybara (https://github.com/google/copybara)"

    // Ensure JAR is downloaded
    dependsOn(downloadCopybara)

    // Set working directory to repository root
    workingDir = rootProject.projectDir

    // Use the copybara configuration classpath
    classpath = configurations["copybara"]

    // Main class for Copybara
    mainClass.set("com.google.copybara.Main")

    // Pass through all command-line arguments
    args = providers.gradleProperty("copybaraArgs")
        .orElse("")
        .map { if (it.isNotEmpty()) it.split(" ") else emptyList() }
        .get()

    // Disable configuration cache for this task to avoid warnings
    notCompatibleWithConfigurationCache("Copybara execution is not compatible with configuration cache")

    // Copybara returns exit code 4 for NO_OP (no changes to sync), which is success
    isIgnoreExitValue = true
    doLast {
        if (executionResult.get().exitValue != 0 && executionResult.get().exitValue != 4) {
            throw GradleException("Copybara failed with exit code ${executionResult.get().exitValue}")
        }
    }
}
