package viaduct.gradle.featureappcontract

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Sync
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
 * [KotlinContractCodegenTask].
 *
 * No `afterEvaluate`. No per-file task registration. One codegen task total.
 */
class KotlinFeatureAppContractPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val codegenClasspath = project.getOrCreateCodegenClasspath()
        DefaultSchemaPlugin.ensureApplied(project)

        val libs = project.extensions
            .getByType<VersionCatalogsExtension>()
            .named("libs")

        val tenantCodegenDependency = libs.findLibrary("viaduct-tenant-codegen").get().get()

        project.pluginManager.apply("com.google.devtools.ksp")
        project.dependencies.add("kspTest", tenantCodegenDependency)

        val contractSchemas = project.configurations.create("contractSchemasResolved") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        val codegenTask = project.tasks.register<KotlinContractCodegenTask>(
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
        val grtOutputFiles = project.files(codegenTask.flatMap { it.grtOutputDir })
        grtOutputFiles.builtBy(codegenTask)
        project.dependencies.add("testImplementation", grtOutputFiles)

        // Bridge task: isolates KSP's internal output path so the assembleTask
        // depends on a typed task reference rather than a string "dependsOn".
        // KSP registers kspTestKotlin lazily, so the string form is still required
        // here (tasks.named() would fail at configuration time).
        val extractKspDescriptors = project.tasks.register<Sync>(
            "extractTestKspRegistryDescriptors"
        ) {
            from(project.layout.buildDirectory.dir("generated/ksp/test/resources/viaduct-registry"))
            into(project.layout.buildDirectory.dir("intermediates/viaduct-test-registry-descriptors"))
            dependsOn("kspTestKotlin")
        }

        val assembleTask = project.tasks.register<AssembleTenantModuleConfigFilesTask>(
            "assembleTestTenantModuleConfigFiles"
        ) {
            group = "viaduct-feature-app"
            description = "Assembles tenant module config from KSP descriptors and contract schemas"

            descriptorDir.set(project.layout.buildDirectory.dir("intermediates/viaduct-test-registry-descriptors"))
            contractSchemaDir.set(project.layout.dir(project.provider { contractSchemas.singleFile }))
            this.codegenClasspath.from(codegenClasspath)
            outputDir.set(
                project.layout.buildDirectory.dir("generated-resources/viaduct-test-registry")
            )

            dependsOn(extractKspDescriptors)
        }

        project.extensions.getByType<JavaPluginExtension>().sourceSets.named("test").configure {
            // Wire generated resolver base sources to the test source set
            java.srcDir(codegenTask.flatMap { it.tenantOutputDir })
            // Wire aggregation output into test resources so it lands on the test classpath
            resources.srcDir(assembleTask.flatMap { it.outputDir })
        }

        project.extensions.create<FeatureAppContractsExtension>(
            "viaductFeatureAppContracts",
            project,
            contractSchemas,
        )
    }
}
