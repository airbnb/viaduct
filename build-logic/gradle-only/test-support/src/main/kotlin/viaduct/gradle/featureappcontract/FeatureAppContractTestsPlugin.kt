package viaduct.gradle.featureappcontract

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import viaduct.gradle.common.getOrCreateCodegenClasspath
import viaduct.gradle.defaultschema.DefaultSchemaPlugin

/**
 * Bilingual consumer plugin for feature-app contract tests.
 *
 * Registers a single Gradle id (`feature-app-contract-tests`) and exposes a
 * `viaductFeatureAppContracts` extension with `kotlin { ... }` and
 * `java { ... }` sub-blocks. Each language's tasks are registered only when its
 * sub-block is configured, so projects opting into only one language pay no
 * cost for the other.
 *
 * No `afterEvaluate`. No per-file task registration. One codegen task per
 * activated language.
 */
class FeatureAppContractTestsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val codegenClasspath = project.getOrCreateCodegenClasspath()
        DefaultSchemaPlugin.ensureApplied(project)

        // Both resolvable configurations are created eagerly (cheap) so the
        // extension can be registered before user DSL runs.
        val kotlinSchemas = project.configurations.create("contractSchemasResolved") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        val javaSchemas = project.configurations.create("javaContractSchemasResolved") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        val testSourceSet = project.extensions
            .getByType<JavaPluginExtension>()
            .sourceSets
            .named("test")

        val kotlinSetup = Runnable {
            wireKotlin(project, kotlinSchemas, codegenClasspath, testSourceSet)
        }
        val javaSetup = Runnable {
            wireJava(project, javaSchemas, codegenClasspath, testSourceSet)
        }

        project.extensions.create<FeatureAppContractsExtension>(
            "viaductFeatureAppContracts",
            project,
            kotlinSchemas,
            kotlinSetup,
            javaSchemas,
            javaSetup,
        )
    }

    private fun wireKotlin(
        project: Project,
        contractSchemas: org.gradle.api.artifacts.Configuration,
        codegenClasspath: org.gradle.api.artifacts.Configuration,
        testSourceSet: org.gradle.api.NamedDomainObjectProvider<SourceSet>,
    ) {
        val libs = project.extensions
            .getByType<VersionCatalogsExtension>()
            .named("libs")
        val tenantCodegenDependency = libs.findLibrary("viaduct-tenant-codegen").get().get()

        // Self-sufficient codegen classpath: contribute the Kotlin generator jar so the
        // task can run regardless of what the consumer declares. The shared
        // viaductCodegenClasspath is additive, so an explicit consumer-side declaration
        // (if any) is harmless.
        project.dependencies.add("viaductCodegenClasspath", tenantCodegenDependency)

        project.pluginManager.apply("com.google.devtools.ksp")
        project.dependencies.add("kspTest", tenantCodegenDependency)

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

        testSourceSet.configure {
            // Wire generated resolver base sources to the test source set
            java.srcDir(codegenTask.flatMap { it.tenantOutputDir })
            // Wire aggregation output into test resources so it lands on the test classpath
            resources.srcDir(assembleTask.flatMap { it.outputDir })
        }
    }

    private fun wireJava(
        project: Project,
        contractSchemas: org.gradle.api.artifacts.Configuration,
        codegenClasspath: org.gradle.api.artifacts.Configuration,
        testSourceSet: org.gradle.api.NamedDomainObjectProvider<SourceSet>,
    ) {
        // Self-sufficient codegen classpath: contribute the Java generator jar so the
        // task can run regardless of what the consumer declares.
        val libs = project.extensions
            .getByType<VersionCatalogsExtension>()
            .named("libs")
        project.dependencies.add(
            "viaductCodegenClasspath",
            libs.findLibrary("viaduct-javaapi-codegen").get().get(),
        )
        // The aggregation CLI lives in :tenant:codegen; ensure it is on the codegen classpath
        // for the Java assembly step too (same CLI, different --executor-factory).
        project.dependencies.add(
            "viaductCodegenClasspath",
            libs.findLibrary("viaduct-tenant-codegen").get().get(),
        )

        val mergedContractSchemasDir =
            project.layout.buildDirectory.dir("intermediates/viaduct-java-contract-schemas")
        val mergeContractSchemas = project.tasks.register<Sync>("mergeJavaContractSchemas") {
            from(contractSchemas)
            into(mergedContractSchemasDir)
        }

        val codegenTask = project.tasks.register<JavaContractCodegenTask>(
            "generateJavaContractTestSources"
        ) {
            group = "viaduct-feature-app"
            description = "Generates Java GRTs and resolver bases from contract schemas"

            contractSchemaDir.from(mergedContractSchemasDir)
            defaultSchemaFile.set(DefaultSchemaPlugin.getDefaultSchemaFileProvider(project))
            this.codegenClasspath.from(codegenClasspath)

            grtOutputDir.set(
                project.layout.buildDirectory.dir("contract-tests/java-grts-merged")
            )
            tenantOutputDir.set(
                project.layout.buildDirectory.dir("contract-tests/java-tenant-merged")
            )

            dependsOn(mergeContractSchemas)
        }

        // Register the Java registry-extractor annotation processor on the test compile, so
        // javac emits per-source-file `viaduct-registry/<pkg>/<Class>.json` descriptors next to
        // the compiled test classes — the Java twin of Kotlin's KSP descriptor stage.
        project.dependencies.add(
            "testAnnotationProcessor",
            libs.findLibrary("viaduct-javaapi-registry-apt").get().get(),
        )

        // Bridge task: copy the APT descriptor output from the test class-output dir into a
        // stable directory the assembly task tracks as a typed input (mirrors the Kotlin KSP
        // bridge Sync). compileTestJava writes resource output to the test classes dir.
        val extractAptDescriptors = project.tasks.register<Sync>(
            "extractTestJavaRegistryDescriptors"
        ) {
            from(
                testSourceSet.map { sourceSet ->
                    sourceSet.output.classesDirs.elements.map { dirs ->
                        dirs.map { it.asFile.resolve("viaduct-registry") }
                    }
                }
            ) {
                // The directory only exists when at least one resolver was processed.
                include("**/*.json")
            }
            into(project.layout.buildDirectory.dir("intermediates/viaduct-java-test-registry-descriptors"))
            dependsOn(project.tasks.named("compileTestJava"))
        }

        val assembleTask = project.tasks.register<AssembleTenantModuleConfigFilesTask>(
            "assembleJavaTestTenantModuleConfigFiles"
        ) {
            group = "viaduct-feature-app"
            description = "Assembles tenant module config from Java APT descriptors and contract schemas"

            descriptorDir.set(project.layout.buildDirectory.dir("intermediates/viaduct-java-test-registry-descriptors"))
            contractSchemaDir.set(mergedContractSchemasDir)
            this.codegenClasspath.from(codegenClasspath)
            executorFactory.set(JAVA_EXECUTOR_FACTORY)
            outputDir.set(
                project.layout.buildDirectory.dir("generated-resources/viaduct-java-test-registry")
            )

            dependsOn(extractAptDescriptors, mergeContractSchemas)
        }

        testSourceSet.configure {
            // Wire Java GRT sources to the test source set (they're .java source files,
            // not bytecode, so srcDir is correct here)
            java.srcDir(codegenTask.flatMap { it.grtOutputDir })
            // Wire generated resolver base sources to the test source set
            java.srcDir(codegenTask.flatMap { it.tenantOutputDir })
            // Wire the assembled registry config into test resources so it lands on the test
            // classpath, where ExecutionRegistryConfigSourceCollector discovers it.
            resources.srcDir(assembleTask.flatMap { it.outputDir })
        }
    }
}
