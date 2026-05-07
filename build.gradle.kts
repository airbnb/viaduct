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
// its root-level publish tasks directly so standalone demoapp builds — both the
// mavenLocal and Sonatype snapshot consumer paths — can find the artifact.
tasks.named("publishToMavenLocal") {
    dependsOn(gradle.includedBuild("remoteresolvers").task(":publishToMavenLocal"))
}
tasks.named("publishToSnapshots") {
    dependsOn(gradle.includedBuild("remoteresolvers").task(":publishAllPublicationsToSnapshotsRepository"))
}
