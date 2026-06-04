package conventions

// This convention plugin wires CVE scanning, SBOM generation, and license
// validation into any module that applies it. It is intended to be applied
// transitively via `conventions.viaduct-publishing` so every published
// artifact gets scanned.
//
// Tools applied:
//   - org.cyclonedx.bom               — SBOM generation in CycloneDX format
//   - com.github.jk1.dependency-license-report — license inventory + allowlist
//   - Trivy CLI (invoked via Gradle Exec on the CycloneDX SBOM) — CVE scan
//
// Tasks added:
//   - trivyScanSbom — runs `trivy sbom` against the CycloneDX SBOM
//   - securityScan  — aggregate of trivyScanSbom + cyclonedxBom + generateLicenseReport
//
// Local prerequisite: install Trivy CLI on PATH (e.g. `brew install trivy`).
// Defaults are tuned for advisory rollout: `severityFloor = "HIGH,CRITICAL"`
// surfaces findings without failing the build (`isIgnoreExitValue = true`).

import com.github.jk1.license.LicenseReportExtension
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.github.jk1.license.render.JsonReportRenderer
import javax.inject.Inject
import org.cyclonedx.gradle.CycloneDxTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import viaduct.gradle.internal.repoRoot

plugins {
    id("org.cyclonedx.bom")
    id("com.github.jk1.dependency-license-report")
}

abstract class SecurityScanningExtension @Inject constructor(objects: ObjectFactory) {
    /** Trivy `--severity` filter. Default surfaces HIGH and CRITICAL findings. */
    val severityFloor: Property<String> = objects.property(String::class.java).convention("HIGH,CRITICAL")

    /** Path (relative to repo root) of the Trivy ignore file. */
    val trivyIgnoreFilePath: Property<String> = objects.property(String::class.java)
        .convention("config/security/.trivyignore")

    /** Path (relative to repo root) of the jk1 license allowlist JSON. */
    val licenseAllowlistFilePath: Property<String> = objects.property(String::class.java)
        .convention("config/security/license-allowlist.json")
}

val securityScanning = extensions.create<SecurityScanningExtension>("securityScanning")

// CycloneDX 1.10.0 invokes Task.project at execution time, which is unsupported under
// Gradle's configuration cache. With the cache on (the repo default) the task doesn't
// fail loudly — it silently resolves nothing and writes an EMPTY SBOM (no `components`).
// Declaring it incompatible makes Gradle run it without the config cache, so the
// dependency graph is actually resolved. This is marked unconditionally (not only inside
// the `java` guard below): the plugin is applied to every project this convention touches,
// including the java-platform `bom`, whose unmarked cyclonedxBom would otherwise hard-fail
// the build under the config cache. Upstream: https://github.com/CycloneDX/cyclonedx-gradle-plugin
tasks.withType(CycloneDxTask::class.java).configureEach {
    notCompatibleWithConfigurationCache(
        "CycloneDX 1.10.0 accesses Task.project at execution time (config-cache incompatible)"
    )
}

// Only configure scans for projects with a Java/Kotlin runtimeClasspath.
// java-platform (BOM) projects are skipped — their dependencies are constraints,
// not a resolvable runtime classpath.
pluginManager.withPlugin("java") {
    val trivyIgnoreFile = repoRoot().file(securityScanning.trivyIgnoreFilePath).map { it.asFile }
    val allowedLicenseFile = repoRoot().file(securityScanning.licenseAllowlistFilePath).map { it.asFile }

    tasks.withType(CycloneDxTask::class.java).configureEach {
        setIncludeConfigs(listOf("runtimeClasspath"))
        setProjectType("library")
        outputFormat.set("json")
        outputName.set("cyclonedx")
        destination.set(layout.buildDirectory.dir("reports/sbom").get().asFile)
    }

    val sbomFile = layout.buildDirectory.file("reports/sbom/cyclonedx.json")
    val trivyReportDir = layout.buildDirectory.dir("reports/trivy")
    val trivyReportFile = layout.buildDirectory.file("reports/trivy/cve-report.json")

    tasks.register<Exec>("trivyScanSbom") {
        group = "verification"
        description = "[security] Scan the CycloneDX SBOM with Trivy CLI for CVEs."
        dependsOn("cyclonedxBom")

        inputs.file(sbomFile)
        inputs.file(trivyIgnoreFile).optional().withPropertyName("trivyIgnoreFile")
        outputs.dir(trivyReportDir)

        executable = "trivy"

        doFirst {
            trivyReportDir.get().asFile.mkdirs()
            val ignore = trivyIgnoreFile.get()
            val baseArgs = listOf(
                "sbom",
                sbomFile.get().asFile.absolutePath,
                "--format", "json",
                "--output", trivyReportFile.get().asFile.absolutePath,
                "--severity", securityScanning.severityFloor.get(),
            )
            setArgs(
                if (ignore.exists()) {
                    baseArgs + listOf("--ignorefile", ignore.absolutePath)
                } else {
                    baseArgs
                }
            )
        }

        // Advisory mode: Trivy exits non-zero when CVEs are found at the floor
        // severity. Surface findings via the JSON report but don't fail the build.
        isIgnoreExitValue = true
    }

    afterEvaluate {
        extensions.configure<LicenseReportExtension> {
            outputDir = layout.buildDirectory.dir("reports/license").get().asFile.absolutePath
            configurations = arrayOf("runtimeClasspath")
            renderers = arrayOf(
                JsonReportRenderer("license-report.json", false),
                InventoryHtmlReportRenderer("license-report.html", "Viaduct License Report")
            )
            filters = arrayOf(LicenseBundleNormalizer())
            allowedLicensesFile = allowedLicenseFile.get()
        }
    }

    tasks.register("securityScan") {
        group = "verification"
        description = "[security] Runs CVE scan, SBOM gen, and license report for this module."
        dependsOn("trivyScanSbom", "cyclonedxBom", "generateLicenseReport")
    }
}
