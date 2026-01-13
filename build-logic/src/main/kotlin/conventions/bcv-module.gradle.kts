package conventions

// This convention plugin adds two aggregate tasks to the project :
// Use this plugin in root or container modules
//
// - runApiDump : runs apiDump on all subprojects that define an apiDump task
// - runApiCheck: runs apiCheck on all subprojects that define an apiCheck task
//
// It is defined as plugin for architectural convention and should be used in the
// container module that includes projects that implements bcv-api.
//

tasks.register("runApiDump") {
    group = "verification"
    description = "Runs apiDump on all modules in this build that define an apiDump task."

    dependsOn(
        subprojects.mapNotNull { p ->
            p.tasks.findByName("apiDump")
        }
    )
}

tasks.register("runApiCheck") {
    group = "verification"
    description = "Runs apiCheck on all modules in this build that define an apiCheck task."

    dependsOn(
        subprojects.mapNotNull { p ->
            p.tasks.findByName("apiCheck")
        }
    )
}
