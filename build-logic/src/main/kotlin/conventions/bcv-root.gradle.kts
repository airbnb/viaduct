package conventions

// Convention plugin applied on the *root* build (the mono-repo).
//
// It delegates two alias tasks:
//
//   runApiDump  -> delegates to includedBuild("core"):runApiDump
//              -> delegates to includedBuild("gradle-plugins"):runApiDump
//   runApiCheck -> delegates to includedBuild("core"):runApiCheck
//              -> delegates to includedBuild("gradle-plugins"):runApiCheck
//

tasks.register("runApiDump") {
    group = "verification"
    description = "Runs BCV apiDump on all Viaduct API modules (core and gradle-plugins)."

    val coreBuild = gradle.includedBuild("core")
    val gradlePluginsBuild = gradle.includedBuild("gradle-plugins")
    dependsOn(coreBuild.task(":runApiDump"))
    dependsOn(gradlePluginsBuild.task(":runApiDump"))
}

tasks.register("runApiCheck") {
    group = "verification"
    description = "Runs BCV apiCheck on all Viaduct API modules (core and gradle-plugins)."

    val coreBuild = gradle.includedBuild("core")
    val gradlePluginsBuild = gradle.includedBuild("gradle-plugins")
    dependsOn(coreBuild.task(":runApiCheck"))
    dependsOn(gradlePluginsBuild.task(":runApiCheck"))
}
