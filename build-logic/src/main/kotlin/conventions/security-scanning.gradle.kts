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
// Trivy exits 0 when it finds CVEs, so findings never fail the build; a Trivy error does.

import buildroot.registerForOrchestrationAggregate
import com.github.jk1.license.LicenseReportExtension
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.github.jk1.license.render.JsonReportRenderer
import com.github.jk1.license.render.ReportRenderer
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
// dependency graph is actually resolved.
// Upstream: https://github.com/CycloneDX/cyclonedx-gradle-plugin
tasks.withType(CycloneDxTask::class.java).configureEach {
    notCompatibleWithConfigurationCache(
        "CycloneDX 1.10.0 accesses Task.project at execution time (config-cache incompatible)"
    )
}

// Only configure scans for projects with a Java/Kotlin runtimeClasspath.
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

        val severityFloor = securityScanning.severityFloor
        inputs.property("severityFloor", severityFloor)
        argumentProviders.add {
            val ignore = trivyIgnoreFile.get()
            listOf(
                "sbom",
                sbomFile.get().asFile.absolutePath,
                "--format", "json",
                "--output", trivyReportFile.get().asFile.absolutePath,
                "--severity", severityFloor.get(),
            ) + if (ignore.exists()) listOf("--ignorefile", ignore.absolutePath) else emptyList()
        }
    }

    afterEvaluate {
        extensions.configure<LicenseReportExtension> {
            outputDir = layout.buildDirectory.dir("reports/license").get().asFile.absolutePath
            configurations = arrayOf("runtimeClasspath")
            renderers = arrayOf<ReportRenderer>(
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
    // Self-report to the orchestration registry instead of having a root project's
    // `subprojects { }` block read this project's task container.
    registerForOrchestrationAggregate("securityScan", "securityScan")
}
