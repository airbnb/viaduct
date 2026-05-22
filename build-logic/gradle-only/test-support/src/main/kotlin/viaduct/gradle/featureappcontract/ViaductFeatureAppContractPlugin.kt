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
 * Consumer plugin for Java contract tests.
 *
 * Resolves schemas from a publisher project's `contractSchemas` configuration and
 * runs Java codegen (source GRTs + resolver base sources) via a single
 * [ViaductJavaContractCodegenTask].
 *
 * No `afterEvaluate`. No per-file task registration. One codegen task total.
 */
class ViaductFeatureAppContractPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val codegenClasspath = project.getOrCreateCodegenClasspath()
        DefaultSchemaPlugin.ensureApplied(project)

        val javaExtension = project.extensions.getByType<JavaPluginExtension>()
        val testSrcSet = javaExtension.sourceSets.getByName("test")

        val contractSchemas = project.configurations.create("javaContractSchemasResolved") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        val codegenTask = project.tasks.register<ViaductJavaContractCodegenTask>(
            "generateJavaContractTestSources"
        ) {
            group = "viaduct-feature-app"
            description = "Generates Java GRTs and resolver bases from contract schemas"

            contractSchemaDir.from(contractSchemas)
            defaultSchemaFile.set(DefaultSchemaPlugin.getDefaultSchemaFileProvider(project))
            this.codegenClasspath.from(codegenClasspath)

            grtOutputDir.set(
                project.layout.buildDirectory.dir("contract-tests/java-grts-merged")
            )
            tenantOutputDir.set(
                project.layout.buildDirectory.dir("contract-tests/java-tenant-merged")
            )
        }

        // Wire Java GRT sources to the test source set (they're .java source files,
        // not bytecode, so srcDir is correct here)
        testSrcSet.java.srcDir(codegenTask.flatMap { it.grtOutputDir })

        // Wire generated resolver base sources to the test source set
        testSrcSet.java.srcDir(codegenTask.flatMap { it.tenantOutputDir })

        project.extensions.create<ViaductFeatureAppContractsExtension>(
            "viaductJavaFeatureAppContracts",
            project,
            contractSchemas
        )
    }
}
