package settings

import java.util.Locale

plugins {
    id("com.gradle.develocity")
}

/**
 * -----------------------------------------------------------------------------
 * Build Scans (Develocity) – Settings
 * -----------------------------------------------------------------------------
 *
 * Purpose
 * -------
 * Configure the Develocity/Build Scan plugin via **Gradle properties** and/or **environment variables**.
 *
 * Precedence
 * ----------
 * 1) Gradle properties (`-Pkey=value` or `gradle.properties`)
 * 2) Environment variables
 * 3) Script defaults
 *
 * Publication policy (decision order)
 * -----------------------------------
 * A build scan will be published if **any** of the following is true, in this order:
 * 1. `buildScan.publishOnFailure == true` **and** the build failed
 * 2. The CLI flag `--scan` is present
 * 3. `buildScan.publish == true`
 * Otherwise, no scan is published.
 *
 * Environment variable mapping
 * ----------------------------
 * | Gradle Property              | Env Var                       | Type    | Default                                      |
 * |------------------------------|-------------------------------|---------|----------------------------------------------|
 * | `buildScan.publish`          | `BUILDSCAN_PUBLISH`           | Boolean | `false`                                      |
 * | `buildScan.publishOnFailure` | `BUILDSCAN_PUBLISHONFAILURE`  | Boolean | `false`                                      |
 * | `buildScan.server`           | `BUILDSCAN_SERVER`            | String  | empty (publishes to public server if empty)  |
 * | `buildScan.autoAcceptTerms`  | `BUILDSCAN_AUTOACCEPTTERMS`   | Boolean | `false` (only relevant for public server)    |
 * | `buildScan.termsUrl`         | `BUILDSCAN_TERMSURL`          | String  | `https://gradle.com/help/legal-terms-of-use` |
 *
 * Where to configure
 * ------------------
 * • **Developer machines (recommended):** `~/.gradle/gradle.properties` for personal defaults.
 *   Example:
 *     buildScan.server=https://gradle-enterprise.example.com
 *     buildScan.publishOnFailure=true
 *
 * • **Project repo (team defaults):** commit non‑secret defaults in `gradle.properties`. Secrets must
 *   go in CI or user-level config, not in VCS.
 *
 * • **CI/CD:** use environment variables (`BUILDSCAN_*`) stored as pipeline/runner secrets. You can still
 *   override per-run with `-P` if needed.
 *
 * Examples
 * --------
 *
 * Publish only failures :
 *
 * buildScan.publish = false
 * buildScan.publishOnFailure = true
 *
 * Publish all builds any result :
 *
 * buildScan.publish = true
 *
 * Do not publish :
 *
 * Do not set any variable. Or explicitly disable publication:
 *
 * buildScan.publish = false
 * buildScan.publishOnFailure = false
 *
 * Notes
 * -----
 * • When publishing to the **public** server (scans.gradle.com), Gradle may prompt for Terms of Use unless
 *   `buildScan.autoAcceptTerms=true` and `buildScan.termsUrl` are set.
 * • The `--scan` flag is an explicit user request to publish; this script honors it as step (2) above.
 * • Logs of every environment configuration are printed during settings evaluation to help diagnose configuration issues.
 */

develocity {
    // Enables or disables build scan publishing entirely. If disabled, no build scan will be created or published.
    val buildScanPublish = providers.boolProp("buildScan.publish", false).get()
    logger.info(">> build-scans : buildScanPublish=$buildScanPublish")

    // Publish only on failure
    val publishOnFailure = providers.boolProp("buildScan.publishOnFailure", false).get()
    logger.info(">> build-scans : publishOnFailure=$publishOnFailure")

    // Publish only if the --scan flag is provided, this is the fallback if neither publishAlways nor publishOnFailure is true
    val withScanFlag = gradle.startParameter.isBuildScan
    logger.info(">> build-scans : withScanFlag=$withScanFlag")

    // If true, the build scan terms of service will be automatically accepted. Use with caution.
    val autoAcceptTerms = providers.boolProp("buildScan.autoAcceptTerms", false).get()
    logger.info(">> build-scans : autoAcceptTerms=$autoAcceptTerms")

    val termsUrl = providers.strProp("buildScan.termsUrl", "https://gradle.com/help/legal-terms-of-use").get()
    logger.info(">> build-scans : termsUrl=$termsUrl")

    val serverUrl = providers.strProp("buildScan.server", "").get()
    if (serverUrl.isNotBlank()) {
        server = serverUrl
    }
    logger.info(">> build-scans : serverUrl=$serverUrl")

    buildScan {
        publishing.onlyIf { ctx ->
            val failed = ctx.buildResult.failures.isNotEmpty()

            when {
                publishOnFailure && failed -> true        // If not failed and publish only when it failed
                withScanFlag -> true                      // --scan flag has priority
                else -> buildScanPublish                  // otherwise check if build scan is enabled
            }
        }

        if (autoAcceptTerms) {
            termsOfUseUrl.set(termsUrl)
            termsOfUseAgree.set("yes")
        }
    }
}

/**
 * Helper to read a boolean property from gradle properties.
 */
fun ProviderFactory.boolProp(name: String, default: Boolean = false, env: String? = null) =
    run {
        val p = gradleProperty(name)
        val e = environmentVariable(env ?: toEnvVarName(name))
        p.orElse(e)
            .map { it.equals("true", true) || it == "1" || it.equals("yes", true) || it.equals("y", true) }
            .orElse(default)
    }

/**
 * Helper to read a string property from gradle properties.
 */
fun ProviderFactory.strProp(name: String, default: String? = null, env: String? = null) =
    run {
        val p = gradleProperty(name)
        val e = environmentVariable(env ?: toEnvVarName(name))
        if (default == null) p.orElse(e) else p.orElse(e).orElse(default)
    }


/**
 * Convert a Gradle property key (e.g., "develocity.server-url") to an environment
 * variable name (e.g., "DEVELOCITY_SERVER_URL"). Non-alphanumeric chars become underscores.
 */
private fun toEnvVarName(key: String): String =
    key.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "_")
