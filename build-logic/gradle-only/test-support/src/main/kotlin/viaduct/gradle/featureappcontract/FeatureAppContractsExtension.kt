package viaduct.gradle.featureappcontract

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * Shared extension for contract test consumer plugins.
 *
 * Registered as `viaductFeatureAppContracts` by both Kotlin and Java consumer plugins.
 * Provides [contractsFrom] to wire the publisher's extracted schemas and compiled
 * contract classes into the consumer project.
 *
 * Each consumer consumes from exactly one publisher project — multi-publisher is
 * not supported.
 */
open class FeatureAppContractsExtension(
    private val project: Project,
    private val contractSchemas: Configuration
) {
    /**
     * Configures this consumer to use contracts from the given publisher project.
     *
     * Eagerly wires:
     * 1. `testImplementation(testFixtures(project(path)))` — so leaf tests can
     *    subclass the contracts. Skipped when [publisherProjectPath] matches the
     *    current project (same-project case).
     * 2. Schema directory — from the publisher's `contractSchemas` configuration
     *    (cross-project) or directly from the `extractContractSchemas` task output
     *    (same-project).
     */
    fun contractsFrom(publisherProjectPath: String) {
        val isSameProject = publisherProjectPath == project.path

        // testFixtures dependency (skip for same-project — test already sees testFixtures)
        if (!isSameProject) {
            project.dependencies.add(
                "testImplementation",
                project.dependencies.testFixtures(
                    project.dependencies.project(mapOf("path" to publisherProjectPath))
                )
            )
        }

        // Schema directory wiring
        if (isSameProject) {
            // Same-project: directly reference the extract task's output.
            // This establishes task ordering automatically via the provider.
            val extractTask = project.tasks.named(
                "extractContractSchemas",
                ContractSchemaExtractTask::class.java
            )
            project.dependencies.add(
                contractSchemas.name,
                project.files(extractTask.map { it.outputDir.get().asFile })
            )
        } else {
            // Cross-project: resolve the publisher's configuration
            project.dependencies.add(
                contractSchemas.name,
                project.dependencies.project(
                    mapOf(
                        "path" to publisherProjectPath,
                        "configuration" to "contractSchemas"
                    )
                )
            )
        }
    }
}
