package viaduct.gradle.featureappcontract

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Publisher plugin for contract test schemas.
 *
 * Extracts GraphQL schemas from `@TestSchema` annotations in testFixtures source files
 * and exposes them via a `contractSchemas` Gradle configuration for consumer plugins.
 *
 * The outgoing configuration is created eagerly (consumers need it to exist at configuration
 * time), but task registration is deferred via `plugins.withId("java-test-fixtures")` until
 * the testFixtures source set exists.
 */
class ViaductContractSchemaPublisherPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Create the outgoing configuration eagerly — consumers need it to exist
        val contractSchemas = project.configurations.create("contractSchemas") {
            isCanBeConsumed = true
            isCanBeResolved = false
        }

        // Defer task registration until java-test-fixtures plugin is applied
        // (the testFixtures source set doesn't exist until then)
        project.plugins.withId("java-test-fixtures") {
            val javaExtension = project.extensions.getByType<JavaPluginExtension>()
            val testFixturesSS = javaExtension.sourceSets.getByName("testFixtures")

            val extractTask = project.tasks.register<ViaductContractSchemaExtractTask>(
                "extractContractSchemas"
            ) {
                group = "viaduct-feature-app"
                description = "Extracts GraphQL schemas from @TestSchema annotations"

                sourceFiles.from(testFixturesSS.allSource)
                outputDir.set(
                    project.layout.buildDirectory.dir("extracted-contract-schemas")
                )
            }

            project.artifacts.add(
                "contractSchemas",
                extractTask.flatMap { it.outputDir }
            )
        }
    }
}
