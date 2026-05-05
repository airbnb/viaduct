plugins {
    id("buildroot.orchestration")
    id("buildroot.versioning")
    id("conventions.bcv-root")
}

orchestration {
    participatingIncludedBuilds.set(
        listOf("core", "gradle-plugins", "publications")
    )
}

// remoteresolvers is a single-module included build (no subprojects), so it doesn't
// expose the orchestration aggregate tasks the root orchestration depends on. Wire
// its root-level publishToMavenLocal directly so standalone demoapp tests can find
// the artifact in Maven Local.
tasks.named("publishToMavenLocal") {
    dependsOn(gradle.includedBuild("remoteresolvers").task(":publishToMavenLocal"))
}
