/**
 * Orchestration plugin for BOTH the top-level root and any included build roots.
 *
 * In ANY build it’s applied to:
 *   - Creates local subproject aggregates (no cycles with root tasks):
 *       :orchestrationBuildAll         -> all subprojects' `build`
 *       :orchestrationCheckAll         -> all subprojects' `check`
 *       :orchestrationCleanAll         -> all subprojects' `clean`
 *       :orchestrationTestAll          -> all subprojects' `Test` tasks
 *       :orchestrationPublishAllToMavenLocal
 *       :orchestrationPublishAllToMavenCentral
 *   - In INCLUDED BUILDS (gradle.parent != null), exposes conventional task names that
 *     delegate to the aggregates: `build`, `check`, `clean`, `test`, `publishToMavenLocal`, `publishToMavenCentral`.
 *
 * In the TOP-LEVEL ROOT ONLY (gradle.parent == null):
 *   - Adds repo-wide tasks spanning root subprojects + selected included builds’ aggregates:
 *       build, check, clean, test, detekt, ktlintCheck, spotlessCheck, dokka, jacoco, ci
 *       publishToMavenLocal, publishToMavenCentral
 *
 * Root configuration:
 *   orchestration {
 *     participatingIncludedBuilds.set(listOf("core", "gradle-plugins"))
 *   }
 */

package buildroot

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.initialization.IncludedBuild
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.create

val orchestrationRegistry = orchestrationRegistryService()

/**
 * Registers an aggregate that depends on every task self-reported under [aggregateKey] via
 * [registerForOrchestrationAggregate], read from the shared [OrchestrationRegistryService]
 * instead of scanning `subprojects { }`'s live task containers.
 */
private fun Project.registerServiceBackedAggregate(
    aggregateName: String,
    aggregateKey: String,
    description: String
) {
    tasks.register(aggregateName) {
        this.group = null
        this.description = description
        usesService(orchestrationRegistry)
        dependsOn(provider { orchestrationRegistry.get().tasksFor(aggregateKey) })
    }
}

// ---------------- Extension (root-only allowlist) ----------------

abstract class OrchestrationExtension {
    /** Names of included builds (their settings' rootProject.name) that should participate. */
    abstract val participatingIncludedBuilds: ListProperty<String>
}
val orchestration = extensions.create<OrchestrationExtension>("orchestration").apply {
    participatingIncludedBuilds.convention(emptyList())
}

// ---------------- Small helpers ----------------

private inline fun Project.ensureTask(
    name: String, group: String, description: String,
    crossinline config: Task.() -> Unit
) {
    val existing = tasks.findByName(name)
    if (existing == null) {
        tasks.register(name) {
            this.group = group
            this.description = description
            config()
        }
    } else {
        tasks.named(name) { config() }
    }
}

private fun Project.tasksNamedInSubprojects(name: String): List<Any> =
    subprojects.map { sp -> sp.tasks.matching { it.name == name } }

private fun <T : Task> Project.tasksOfTypeInSubprojects(type: Class<T>): List<Any> =
    subprojects.map { sp -> sp.tasks.withType(type) }

private fun Project.tasksStartingWithInSubprojects(prefix: String): List<Any> =
    subprojects.map { sp -> sp.tasks.matching { it.name.startsWith(prefix) } }

private fun Project.participatingIncludedBuilds(): List<IncludedBuild> {
    val wanted = orchestration.participatingIncludedBuilds.get()
    return gradle.includedBuilds.filter { it.name in wanted }
}

/**
 * DRY helper: register a local aggregate that depends on subproject tasks by name and/or type.
 * - Wires lazily during configuration (configureEach)
 * - Adds a final catch-up at end of configuration via task PATH (config-cache safe; no realization)
 */
private fun Project.registerSubprojectAggregate(
    aggregateName: String,
    description: String,
    taskNames: Set<String> = emptySet(),
    taskTypes: List<Class<out Task>> = emptyList()
) {
    val agg = tasks.register(aggregateName) {
        this.group = null
        this.description = description
    }

    // Lazy wiring during configuration
    subprojects {
        if (taskNames.isNotEmpty()) {
            tasks.matching { it.name in taskNames }.configureEach {
                agg.configure { dependsOn(this@configureEach) }
            }
        }
        taskTypes.forEach { t ->
            tasks.withType(t).configureEach {
                agg.configure { dependsOn(this@configureEach) }
            }
        }
    }

    // Final catch-up once all projects are configured (use PATH strings; no task realization)
    gradle.projectsEvaluated {
        val depPaths = mutableListOf<String>()
        subprojects.forEach { sp ->
            taskNames.forEach { n ->
                if (sp.tasks.findByName(n) != null) depPaths += "${sp.path}:$n"
            }
            taskTypes.forEach { t ->
                sp.tasks.withType(t).forEach { depPaths += it.path }
            }
        }
        if (depPaths.isNotEmpty()) {
            tasks.named(aggregateName) { dependsOn(depPaths) }
        }
    }
}

/** DRY helper: in INCLUDED BUILDS, expose a conventional task that delegates to an aggregate. */
private fun Project.aliasConventionalTaskToAggregate(
    conventionalName: String,
    aggregateName: String,
    group: String,
    description: String
) {
    val existing = tasks.findByName(conventionalName)
    if (existing == null) {
        tasks.register(conventionalName) {
            this.group = group
            this.description = description
            dependsOn(aggregateName)
        }
    } else {
        tasks.named(conventionalName) { dependsOn(aggregateName) }
    }
}

// ---------------- Local aggregates (created in EVERY build where applied) ----------------

// Build/check/test
registerSubprojectAggregate(
    aggregateName = "orchestrationBuildAll",
    description = "[orchestration] Builds all SUBPROJECTS in THIS build.",
    taskNames = setOf("build")
)
registerSubprojectAggregate(
    aggregateName = "orchestrationCheckAll",
    description = "[orchestration] Checks all SUBPROJECTS in THIS build.",
    taskNames = setOf("check")
)
registerSubprojectAggregate(
    aggregateName = "orchestrationCleanAll",
    description = "[orchestration] Cleans all SUBPROJECTS in THIS build.",
    taskNames = setOf("clean")
)
// Also clean THIS build's root project build directory; subproject-only aggregates miss it.
val cleanRootBuildDir = tasks.register<Delete>("orchestrationCleanRootBuildDir") {
    description = "[orchestration] Cleans this build's root project build directory."
    delete(layout.buildDirectory)
}
tasks.named("orchestrationCleanAll") { dependsOn(cleanRootBuildDir) }
registerSubprojectAggregate(
    aggregateName = "orchestrationTestAll",
    description = "[orchestration] Tests all SUBPROJECTS in THIS build.",
    taskTypes = listOf(Test::class.java)
)
// detekt, ktlintCheck, findWarningsForCleanup, and securityScan are each added by exactly one
// convention plugin, which self-reports its task to the shared OrchestrationRegistryService
// (see conventions.kotlin-static-analysis / conventions.security-scanning). Reading the registry
// here avoids this root project scanning every subproject's live task container.
registerServiceBackedAggregate(
    aggregateName = "orchestrationDetektAll",
    aggregateKey = "detekt",
    description = "[orchestration] Runs detekt on all SUBPROJECTS in THIS build.",
)
registerServiceBackedAggregate(
    aggregateName = "orchestrationKtlintCheckAll",
    aggregateKey = "ktlintCheck",
    description = "[orchestration] Runs ktlintCheck on all SUBPROJECTS in THIS build.",
)
registerSubprojectAggregate(
    aggregateName = "orchestrationSpotlessCheckAll",
    description = "[orchestration] Runs spotlessCheck on all SUBPROJECTS in THIS build.",
    taskNames = setOf("spotlessCheck")
)
registerServiceBackedAggregate(
    aggregateName = "orchestrationFindWarningsForCleanupAll",
    aggregateKey = "findWarningsForCleanup",
    description = "[orchestration] Runs findWarningsForCleanup on all SUBPROJECTS in THIS build.",
)
registerServiceBackedAggregate(
    aggregateName = "orchestrationSecurityScanAll",
    aggregateKey = "securityScan",
    description = "[orchestration] Runs CVE/SBOM/license scans on all SUBPROJECTS in THIS build.",
)

// CI-oriented aggregate: compile main + test sources without running tests or producing jars
registerSubprojectAggregate(
    aggregateName = "orchestrationBuildRepoForCI",
    description = "[orchestration] Compiles main and test sources for all SUBPROJECTS in THIS build.",
    taskNames = setOf("classes", "testClasses")
)

// Publishing: each publish task is added by conventions.viaduct-publishing, which self-reports
// to the registry -- see the note above the static-analysis aggregates.
registerServiceBackedAggregate(
    aggregateName = "orchestrationPublishAllToMavenLocal",
    aggregateKey = "publishToMavenLocal",
    description = "[orchestration] Publishes all publishable SUBPROJECTS in THIS build to mavenLocal.",
)
registerServiceBackedAggregate(
    aggregateName = "orchestrationPublishAllToMavenCentral",
    aggregateKey = "publishToMavenCentral",
    description = "[orchestration] Publishes all publishable SUBPROJECTS in THIS build to Maven Central.",
)
registerServiceBackedAggregate(
    aggregateName = "orchestrationPublishAllToSnapshots",
    aggregateKey = "publishToSnapshots",
    description = "[orchestration] Publishes all publishable SUBPROJECTS in THIS build to Central Portal snapshots.",
)

// ---------------- In INCLUDED BUILDS: alias conventional tasks to aggregates ----------------

if (gradle.parent != null) {
    aliasConventionalTaskToAggregate(
        conventionalName = "build",
        aggregateName = "orchestrationBuildAll",
        group = "build",
        description = "Builds all subprojects in this included build."
    )
    aliasConventionalTaskToAggregate(
        conventionalName = "check",
        aggregateName = "orchestrationCheckAll",
        group = "verification",
        description = "Checks all subprojects in this included build."
    )
    aliasConventionalTaskToAggregate(
        conventionalName = "clean",
        aggregateName = "orchestrationCleanAll",
        group = "build",
        description = "Cleans all subprojects in this included build."
    )
    aliasConventionalTaskToAggregate(
        conventionalName = "test",
        aggregateName = "orchestrationTestAll",
        group = "verification",
        description = "Runs all tests in this included build."
    )
    aliasConventionalTaskToAggregate(
        conventionalName = "publishToMavenLocal",
        aggregateName = "orchestrationPublishAllToMavenLocal",
        group = "publishing",
        description = "Publishes all publishable subprojects in this included build to mavenLocal."
    )
    aliasConventionalTaskToAggregate(
        conventionalName = "publishToMavenCentral",
        aggregateName = "orchestrationPublishAllToMavenCentral",
        group = "publishing",
        description = "Publishes all publishable subprojects in this included build to Maven Central."
    )
    aliasConventionalTaskToAggregate(
        conventionalName = "publishToSnapshots",
        aggregateName = "orchestrationPublishAllToSnapshots",
        group = "publishing",
        description = "Publishes all publishable subprojects in this included build to Central Portal snapshots."
    )
    aliasConventionalTaskToAggregate(
        conventionalName = "detekt",
        aggregateName = "orchestrationDetektAll",
        group = "verification",
        description = "Runs detekt on all subprojects in this included build."
    )
    aliasConventionalTaskToAggregate(
        conventionalName = "ktlintCheck",
        aggregateName = "orchestrationKtlintCheckAll",
        group = "verification",
        description = "Runs ktlintCheck on all subprojects in this included build."
    )
    aliasConventionalTaskToAggregate(
        conventionalName = "spotlessCheck",
        aggregateName = "orchestrationSpotlessCheckAll",
        group = "verification",
        description = "Runs spotlessCheck on all subprojects in this included build."
    )
    aliasConventionalTaskToAggregate(
        conventionalName = "findWarningsForCleanup",
        aggregateName = "orchestrationFindWarningsForCleanupAll",
        group = "verification",
        description = "Runs findWarningsForCleanup on all subprojects in this included build."
    )
    aliasConventionalTaskToAggregate(
        conventionalName = "securityScan",
        aggregateName = "orchestrationSecurityScanAll",
        group = "verification",
        description = "Runs CVE/SBOM/license scans on all subprojects in this included build."
    )
}

// ---------------- Workspace-wide tasks (ROOT ONLY) ----------------

if (gradle.parent == null) {
    // build: root subprojects + included builds' aggregate
    ensureTask("build", "build", "Builds root subprojects and participating included builds.") {
        dependsOn(tasksNamedInSubprojects("build"))
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationBuildAll") })
    }

    // check: root subprojects + included builds' aggregate
    ensureTask("check", "verification", "Runs checks across root and participating included builds.") {
        dependsOn(tasksNamedInSubprojects("check"))
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationCheckAll") })
    }

    // clean: root subprojects + included builds' aggregate
    ensureTask("clean", "build", "Runs cleans across root and participating included builds.") {
        dependsOn(tasksNamedInSubprojects("clean"))
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationCleanAll") })
    }

    // test: root subprojects' Test tasks + included builds' aggregate
    ensureTask("test", "verification", "Runs tests in root subprojects and participating included builds.") {
        dependsOn(tasksOfTypeInSubprojects(Test::class.java))
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationTestAll") })
    }

    // publish local: root subprojects (via registry) + included builds' aggregate (NO dependency on root aggregate)
    ensureTask("publishToMavenLocal", "publishing", "Publishes root subprojects + participating included builds to mavenLocal.") {
        usesService(orchestrationRegistry)
        dependsOn(provider { orchestrationRegistry.get().tasksFor("publishToMavenLocal") })
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationPublishAllToMavenLocal") })
    }

    // publish central: root subprojects (via registry) + included builds' aggregate (NO dependency on root aggregate)
    ensureTask("publishToMavenCentral", "publishing", "Publishes root subprojects + participating included builds to Maven Central.") {
        usesService(orchestrationRegistry)
        dependsOn(provider { orchestrationRegistry.get().tasksFor("publishToMavenCentral") })
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationPublishAllToMavenCentral") })
    }

    // publish snapshots: root subprojects (via registry) + included builds' aggregate
    ensureTask("publishToSnapshots", "publishing", "Publishes root subprojects + participating included builds to Central Portal snapshots.") {
        usesService(orchestrationRegistry)
        dependsOn(provider { orchestrationRegistry.get().tasksFor("publishToSnapshots") })
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationPublishAllToSnapshots") })
    }

    // detekt: root subprojects (via registry) + included builds' aggregate
    ensureTask("detekt", "verification", "Runs detekt across root and participating included builds.") {
        usesService(orchestrationRegistry)
        dependsOn(provider { orchestrationRegistry.get().tasksFor("detekt") })
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationDetektAll") })
    }

    // ktlintCheck: root subprojects (via registry) + included builds' aggregate
    ensureTask("ktlintCheck", "verification", "Runs ktlintCheck across root and participating included builds.") {
        usesService(orchestrationRegistry)
        dependsOn(provider { orchestrationRegistry.get().tasksFor("ktlintCheck") })
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationKtlintCheckAll") })
    }

    // spotlessCheck: root subprojects + included builds' aggregate
    ensureTask("spotlessCheck", "verification", "Runs spotlessCheck across root and participating included builds.") {
        dependsOn(tasksNamedInSubprojects("spotlessCheck"))
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationSpotlessCheckAll") })
    }

    // findWarningsForCleanup: root subprojects (via registry) + included builds' aggregate
    ensureTask("findWarningsForCleanup", "verification", "Runs findWarningsForCleanup across root and participating included builds.") {
        usesService(orchestrationRegistry)
        dependsOn(provider { orchestrationRegistry.get().tasksFor("findWarningsForCleanup") })
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationFindWarningsForCleanupAll") })
    }

    // securityScan: root subprojects (via registry) + included builds' aggregate
    ensureTask("securityScan", "verification", "Runs CVE/SBOM/license scans across root and participating included builds.") {
        usesService(orchestrationRegistry)
        dependsOn(provider { orchestrationRegistry.get().tasksFor("securityScan") })
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationSecurityScanAll") })
    }

    // buildRepoForCI: compile main + test classes across the repo (no test execution, no jars)
    ensureTask("buildRepoForCI", "build", "Compiles main and test classes across the repo for CI cache priming.") {
        dependsOn("orchestrationBuildRepoForCI")
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationBuildRepoForCI") })
    }
}
