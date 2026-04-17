package viaduct.gradle.featureappcontract

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
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

        // Apply KSP and wire the registry-extractor processor for test compilation
        val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
        project.pluginManager.apply("com.google.devtools.ksp")
        project.dependencies.add("kspTest", libs.findLibrary("viaduct-tenant-codegen").get())

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

        // Register the aggregation task that combines KSP descriptors + schema into
        // the tenant module config file (META-INF/viaduct/modules/<tenantpkg>.json).
        //
        // dependsOn (by name) is required for two reasons:
        // 1. We consume a subdirectory of the KSP task's resource output, so Gradle
        //    cannot infer the task dependency from the path alone.
        // 2. KSP registers the kspTestKotlin task lazily (in response to Kotlin
        //    compilation task creation), so tasks.named() would fail here. The
        //    string form defers resolution to execution-graph construction time.
        val kspResourceDir = project.layout.buildDirectory.dir(
            "generated/ksp/test/resources/viaduct-registry"
        )

        val assembleTask = project.tasks.register<AssembleTenantModuleConfigFilesTask>(
            "assembleTestTenantModuleConfigFiles"
        ) {
            group = "viaduct-feature-app"
            description = "Assembles tenant module config from KSP descriptors and contract schemas"

            descriptorDir.set(kspResourceDir)
            contractSchemaDir.from(contractSchemas)
            this.codegenClasspath.from(codegenClasspath)
            outputDir.set(project.layout.buildDirectory.dir("generated-resources/viaduct-test-registry"))

            dependsOn("kspTestKotlin")
        }

        // Wire aggregation output into test resources so it lands on the test classpath
        testSrcSet.resources.srcDir(assembleTask.flatMap { it.outputDir })

        project.extensions.create<ViaductFeatureAppContractsExtension>(
            "viaductFeatureAppContracts",
            project,
            contractSchemas
        )
    }
}
