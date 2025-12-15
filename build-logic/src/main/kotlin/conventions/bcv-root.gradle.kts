package conventions

// Convention plugin applied on the *root* build (the mono-repo).
//
// It delegates two alias tasks:
//
//   runApiDump  -> delegates to includedBuild("core"):runApiDump
//   runApiCheck -> delegates to includedBuild("core"):runApiCheck
//

tasks.register("runApiDump") {
    group = "verification"
    description = "Runs BCV apiDump on all Viaduct core API modules (via included build 'core')."

    val coreBuild = gradle.includedBuild("core")
    dependsOn(coreBuild.task(":runApiDump"))
}

tasks.register("runApiCheck") {
    group = "verification"
    description = "Runs BCV apiCheck on all Viaduct core API modules (via included build 'core')."

    val coreBuild = gradle.includedBuild("core")
    dependsOn(coreBuild.task(":runApiCheck"))
}
