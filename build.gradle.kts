plugins {
    id("buildroot.orchestration")
    id("buildroot.versioning")
    id("conventions.bcv-root")
}


orchestration {
    participatingIncludedBuilds.set(
        listOf("core", "gradle-plugins")
    )
}

tasks.named("publishToMavenLocal") {
    dependsOn(gradle.includedBuild("build-logic").task(":build-shared:publishToMavenLocal"))
}
