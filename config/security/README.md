# Viaduct OSS Security Scanning Configuration

This directory holds configuration for the CVE + license scanning pipeline
applied to all publishable Viaduct OSS modules.

## Files

| File | Purpose |
|---|---|
| `.trivyignore` | Trivy suppression list (CVE IDs, optional `exp:YYYY-MM-DD` expiry) |
| `license-allowlist.json` | jk1 license-report allowlist (consumed by the `checkLicense` task) |

## Running Scans Locally

**Prerequisite:** install the Trivy CLI on your PATH.

```bash
# macOS
brew install trivy

# Other platforms: https://aquasecurity.github.io/trivy/latest/getting-started/installation/
```

```bash
cd projects/viaduct/oss

# Run the full security scan (CVE + SBOM + license report) for all modules
./gradlew securityScan

# Per-module scan (faster while iterating)
./gradlew :core:engine:api:securityScan

# Inspect reports
ls -R */build/reports/{trivy,sbom,license}
```

Trivy auto-fetches its vulnerability DB on first run and caches it at
`~/.cache/trivy/`. Subsequent runs reuse the cache.

## SBOM in the CI job summary

The `cve-parity` job in `.github/workflows/security-scan.yml` renders every
publication's CycloneDX SBOM into the **GitHub Actions job summary** — an
overview table plus a collapsible component list (dependency, version, license)
per fat JAR — and uploads the raw `cyclonedx.json` files in the
`security-scan-reports` artifact. This lets you see what each published fat JAR
bundles without building or downloading anything.

To reproduce that summary locally:

```bash
# Generate the SBOMs (no Trivy required for SBOM generation alone)
./gradlew -p publications cyclonedxBom

# Format them into the same Markdown CI appends to the job summary
python3 .github/scripts/format_sbom_summary.py publications
```

> `cyclonedxBom` runs without Gradle's configuration cache by design: the
> CycloneDX 1.10.0 plugin is config-cache-incompatible and would otherwise emit
> an empty SBOM. The `conventions.security-scanning` plugin marks the task so
> this is handled automatically — no extra flags needed.

## Suppression Policy

`.trivyignore` is plain text — one CVE ID per line. To suppress a finding:

1. Add a **comment block** above the CVE entry containing:
   - Your GitHub handle and the ISO date of the suppression.
   - The reason (link to upstream issue, internal triage doc, or short
     justification).
2. Set an **expiry date** with `exp:YYYY-MM-DD` (Trivy enforces this — the
   suppression auto-expires). The expiry must be no more than 90 days from
   the suppression date.
3. A suppression must NEVER use a wildcard or omit the CVE ID — every entry
   targets one specific finding.

Pull requests that add suppressions require security-team review.

Example:

```
# geovanne_duarte 2026-05-26: false-positive — only affects guava server
# module which Viaduct doesn't bundle. https://github.com/google/guava/issues/XXXX
CVE-XXXX-YYYYY exp:2026-08-24
```

## License Policy

`license-allowlist.json` follows the [jk1 license-report
schema](https://github.com/jk1/Gradle-License-Report#license-checks). It
lists the SPDX identifiers and common aliases the build accepts. To add a
new license, open a PR with:

- The SPDX identifier and any common alias strings used in the wild.
- Justification (typically: the dependency is essential and the license is
  permissive enough for distribution alongside Apache-2.0 code).

Blocked-by-default licenses (GPL, AGPL) require legal review and are not
added without explicit approval.

## References

Fuller upstream documentation for the tools this pipeline uses:

**Trivy** (CVE scanning)
- Docs home: https://trivy.dev/
- Suppression / `.trivyignore` filtering format: https://trivy.dev/latest/docs/configuration/filtering/
- SBOM scanning (CycloneDX/SPDX input): https://trivy.dev/latest/docs/target/sbom/
- Installation: https://trivy.dev/latest/getting-started/installation/

**CycloneDX** (SBOM generation)
- Gradle plugin: https://github.com/CycloneDX/cyclonedx-gradle-plugin
- Specification: https://cyclonedx.org/

**jk1 Gradle License Report** (license validation)
- Plugin docs: https://github.com/jk1/Gradle-License-Report
- License-checks configuration: https://github.com/jk1/Gradle-License-Report#license-checks
