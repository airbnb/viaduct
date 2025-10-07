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
 *       build, check, clean, test, dokka, jacoco, ci
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
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.create

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
    optionalTaskNames: Set<String> = emptySet(),
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
        if (optionalTaskNames.isNotEmpty()) {
            tasks.matching { it.name in optionalTaskNames }.configureEach {
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
            optionalTaskNames.forEach { n ->
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
registerSubprojectAggregate(
    aggregateName = "orchestrationTestAll",
    description = "[orchestration] Tests all SUBPROJECTS in THIS build.",
    taskTypes = listOf(Test::class.java)
)

// Publishing
registerSubprojectAggregate(
    aggregateName = "orchestrationPublishAllToMavenLocal",
    description = "[orchestration] Publishes all publishable SUBPROJECTS in THIS build to mavenLocal.",
    taskNames = setOf("publishToMavenLocal")
)
registerSubprojectAggregate(
    aggregateName = "orchestrationPublishAllToMavenCentral",
    description = "[orchestration] Publishes all publishable SUBPROJECTS in THIS build to Maven Central.",
    taskNames = setOf("publishAllPublicationsToMavenCentralRepository"),
    optionalTaskNames = setOf("publishToMavenCentral")
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

    // publish local: root subprojects + included builds' aggregate (NO dependency on root aggregate)
    ensureTask("publishToMavenLocal", "publishing", "Publishes root subprojects + participating included builds to mavenLocal.") {
        dependsOn(tasksNamedInSubprojects("publishToMavenLocal"))
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationPublishAllToMavenLocal") })
    }

    // publish central: root subprojects + included builds' aggregate (NO dependency on root aggregate)
    ensureTask("publishToMavenCentral", "publishing", "Publishes root subprojects + participating included builds to Maven Central.") {
        dependsOn(tasksNamedInSubprojects("publishAllPublicationsToMavenCentralRepository"))
        dependsOn(tasksNamedInSubprojects("publishToMavenCentral"))
        dependsOn(participatingIncludedBuilds().map { it.task(":orchestrationPublishAllToMavenCentral") })
    }
}
