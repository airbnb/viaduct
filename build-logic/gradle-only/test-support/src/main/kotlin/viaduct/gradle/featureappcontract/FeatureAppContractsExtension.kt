package viaduct.gradle.featureappcontract

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.model.ObjectFactory

/**
 * Shared extension for the contract test consumer plugin.
 *
 * Registered as `viaductFeatureAppContracts`. Exposes two [LanguageContracts]
 * sub-blocks — `kotlin` and `java` — but **only one may be configured per
 * project**:
 *
 * ```
 * viaductFeatureAppContracts {
 *     kotlin { contractsFrom(":tenant:runtime") }   // OR java { ... }, not both
 * }
 * ```
 *
 * The two languages cannot coexist in a single project's test source set:
 * Kotlin and Java contract codegen emit GRTs and resolver bases under the
 * *same* fully-qualified class names for a given schema (e.g.
 * `viaduct.tenant.runtime.executioncontext.Query`), so sharing one compile
 * classpath produces duplicate-class / redeclaration errors. Configuring both
 * blocks therefore fails fast with a clear message. To run both Kotlin and
 * Java contract tests, use two separate projects.
 *
 * Each sub-block's setup is run on first activation (first call to
 * `contractsFrom`) so language-specific tasks (and KSP for Kotlin) only register
 * when actually requested.
 */
open class FeatureAppContractsExtension
    @Inject
    constructor(
        objects: ObjectFactory,
        project: Project,
        kotlinSchemas: Configuration,
        kotlinSetup: Runnable,
        javaSchemas: Configuration,
        javaSetup: Runnable,
    ) {
        /** Tracks which language block has been activated, to enforce at-most-one. */
        private var activatedLanguage: String? = null

        /**
         * Wraps a language setup with the cross-language exclusivity check. The
         * check runs on first activation of either block; a second language then
         * fails fast.
         */
        private fun guard(
            language: String,
            setup: Runnable,
        ): Runnable =
            Runnable {
                val current = activatedLanguage
                require(current == null || current == language) {
                    "viaductFeatureAppContracts supports only one language per project, but both " +
                        "'$current' and '$language' blocks were configured. Kotlin and Java contract " +
                        "codegen emit the same GRT and resolver-base class names for a given schema, so " +
                        "they cannot share a test source set. Use separate projects for Kotlin and Java " +
                        "contract tests."
                }
                activatedLanguage = language
                setup.run()
            }

        val kotlin: LanguageContracts =
            objects.newInstance(
                LanguageContracts::class.java,
                project,
                kotlinSchemas,
                guard("kotlin", kotlinSetup),
            )

        val java: LanguageContracts =
            objects.newInstance(
                LanguageContracts::class.java,
                project,
                javaSchemas,
                guard("java", javaSetup),
            )

        fun kotlin(action: Action<LanguageContracts>) = action.execute(kotlin)

        fun java(action: Action<LanguageContracts>) = action.execute(java)
    }

/**
 * Per-language contracts sub-extension.
 *
 * Wires the publisher's extracted schemas (and `testFixtures` dependency) into
 * the consumer's resolvable configuration, and triggers the language-specific
 * plugin setup the first time `contractsFrom` is invoked.
 */
open class LanguageContracts
    @Inject
    constructor(
        private val project: Project,
        private val contractSchemas: Configuration,
        private val setup: Runnable,
    ) {
        private var activated = false

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
         *
         * The first invocation also fires the language-specific plugin setup
         * (codegen task registration, KSP wiring, etc.).
         */
        fun contractsFrom(publisherProjectPath: String) {
            if (!activated) {
                activated = true
                setup.run()
            }

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
                    project.files(extractTask.flatMap { it.outputDir })
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
