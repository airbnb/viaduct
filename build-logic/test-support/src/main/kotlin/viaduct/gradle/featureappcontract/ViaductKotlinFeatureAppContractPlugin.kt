package viaduct.gradle.featureappcontract

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import viaduct.gradle.common.getOrCreateCodegenClasspath
import viaduct.gradle.defaultschema.DefaultSchemaPlugin

/**
 * Consumer plugin for Kotlin contract tests.
 *
 * Resolves schemas from a publisher project's `contractSchemas` configuration and
 * runs Kotlin codegen (bytecode GRTs + resolver base sources) via a single
 * [ViaductKotlinContractCodegenTask].
 *
 * No `afterEvaluate`. No per-file task registration. One codegen task total.
 */
class ViaductKotlinFeatureAppContractPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val codegenClasspath = project.getOrCreateCodegenClasspath()
        DefaultSchemaPlugin.ensureApplied(project)

        val javaExtension = project.extensions.getByType<JavaPluginExtension>()
        val testSrcSet = javaExtension.sourceSets.getByName("test")

        val contractSchemas = project.configurations.create("contractSchemasResolved") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        val codegenTask = project.tasks.register<ViaductKotlinContractCodegenTask>(
            "generateContractTestSources"
        ) {
            group = "viaduct-feature-app"
            description = "Generates Kotlin GRTs and resolver bases from contract schemas"

            contractSchemaDir.from(contractSchemas)
            defaultSchemaFile.set(DefaultSchemaPlugin.getDefaultSchemaFileProvider(project))
            this.codegenClasspath.from(codegenClasspath)

            grtOutputDir.set(
                project.layout.buildDirectory.dir("contract-tests/grts-merged")
            )
            tenantOutputDir.set(
                project.layout.buildDirectory.dir("contract-tests/tenant-merged")
            )
        }

        // Wire GRT bytecode as testImplementation dependency (not output.dir —
        // output.dir doesn't make .class files visible to the Kotlin compiler)
        val grtOutputDirProvider = codegenTask.flatMap { it.grtOutputDir }
        project.dependencies.add(
            "testImplementation",
            project.files(grtOutputDirProvider).also { it.builtBy(codegenTask) }
        )

        // Wire generated resolver base sources to the test source set
        testSrcSet.java.srcDir(codegenTask.flatMap { it.tenantOutputDir })

        project.extensions.create<ViaductFeatureAppContractsExtension>(
            "viaductFeatureAppContracts",
            project,
            contractSchemas
        )
    }
}
