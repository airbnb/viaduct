package conventions

plugins {
    id("org.gradle.test-retry")
}

// Retry flaky tests only on CI, so local runs stay honest by default. Enable via the
// `CI` env var (set by GitHub Actions) or `-PonCI=true`. A test that fails and then
// passes on retry is still reported as flaky in the test report and the Develocity
// build scan, so flakiness stays visible rather than masked.
val onCi =
    providers.gradleProperty("onCI")
        .orElse(providers.environmentVariable("CI"))
        .map(String::toBoolean)
        .getOrElse(false)

tasks.withType<Test>().configureEach {
    retry {
        maxRetries.set(if (onCi) 2 else 0)
        maxFailures.set(5) // >5 failures = systemic breakage; don't retry
        failOnPassedAfterRetry.set(false) // passed-on-retry stays green
    }
}
