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

// remoteresolvers is a single-module included build, so the orchestration's
// subproject-fanout aggregates don't reach it. Wire its publishToMavenLocal
// directly so rrp-server (a sibling included build) can resolve the artifact.
tasks.named("publishToMavenLocal") {
    dependsOn(gradle.includedBuild("remoteresolvers").task(":publishToMavenLocal"))
}
