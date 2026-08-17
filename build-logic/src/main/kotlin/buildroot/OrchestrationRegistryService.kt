package buildroot

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Build-scoped service that lets a project self-report a task under a well-known aggregate key
 * (e.g. "detekt", "securityScan"), so a root aggregate task can depend on every contributor
 * without reading other projects' task containers. Registration and lookup are backed by
 * concurrent collections because projects configure concurrently under parallel configuration.
 */
abstract class OrchestrationRegistryService : BuildService<BuildServiceParameters.None> {
    private val taskPathsByAggregateKey = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

    fun register(
        aggregateKey: String,
        taskPath: String
    ) {
        taskPathsByAggregateKey.computeIfAbsent(aggregateKey) { CopyOnWriteArrayList() }.add(taskPath)
    }

    fun tasksFor(aggregateKey: String): List<String> = taskPathsByAggregateKey[aggregateKey]?.toList() ?: emptyList()

    companion object {
        const val NAME = "OrchestrationRegistryService"
    }
}

/** Gets or registers this build's [OrchestrationRegistryService]. */
fun Project.orchestrationRegistryService() = gradle.sharedServices.registerIfAbsent(OrchestrationRegistryService.NAME, OrchestrationRegistryService::class.java) {}

/**
 * Registers [taskName] under [aggregateKey] once this project has finished evaluating, if a task
 * by that name exists. Deferred to `afterEvaluate` (rather than checked at the call site) so this
 * works regardless of whether [taskName] was registered by a plugin applied earlier in the
 * `plugins { }` block, by this same convention script, or lazily by a third-party plugin -- by the
 * time a project's `afterEvaluate` runs, every task it will ever register is at least name-known
 * in its own `tasks` container. This is a project acting on itself; nothing here reads another
 * project's task container. Root aggregate tasks then depend on the registered paths via
 * [orchestrationRegistryService] from `gradle.projectsEvaluated`, which fires only after every
 * project's `afterEvaluate` (including this one) has completed.
 */
fun Project.registerForOrchestrationAggregate(
    aggregateKey: String,
    taskName: String
) {
    val service = orchestrationRegistryService()
    afterEvaluate {
        if (taskName in tasks.names) {
            service.get().register(aggregateKey, "$path:$taskName")
        }
    }
}
