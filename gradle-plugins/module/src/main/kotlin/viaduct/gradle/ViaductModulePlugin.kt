package viaduct.gradle

import centralSchemaDirectoryName
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.ViaductPluginCommon.createOrGetCodegenClasspath
import viaduct.gradle.ViaductPluginCommon.pluginVersion
import viaduct.gradle.ViaductPluginCommon.validateModuleProjectPlacement
import viaduct.gradle.task.AssembleTenantModuleConfigFileTask
import viaduct.gradle.task.GenerateResolverBasesTask

@InternalApi
class ViaductModulePlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            val moduleLayout = ViaductModulePluginSupport.modulePackageLayout(
                this,
                validateModuleProjectPlacement("com.airbnb.viaduct.module-gradle-plugin"),
            )

            val moduleExt = extensions.findByType(ViaductModuleExtension::class.java)
                ?: extensions.create("viaductModule", ViaductModuleExtension::class.java, objects)

            ViaductModulePluginSupport.configureDirectModuleDependencyChecks(this)
            ViaductModulePluginSupport.configureModulePackageSuffixConvention(this, moduleExt)
            ViaductModulePluginSupport.validateContainingApplicationProjectPlugin(
                this,
                "com.airbnb.viaduct.module-gradle-plugin",
            )

            val grtIncomingCfg = ViaductModulePluginSupport.createGRTIncomingConfiguration(
                this,
                ViaductPluginCommon.Configs.GRT_CLASSES_KOTLIN_INCOMING,
                ViaductPluginCommon.Kind.KOTLIN_GRT_CLASSES,
            )

            val assembleSchemaPartitionTask =
                ViaductModulePluginSupport.setupAssembleSchemaPartitionTask(this, moduleLayout)
            ViaductModulePluginSupport.setupOutgoingConfigurationForPartitionSchema(this, assembleSchemaPartitionTask)

            val centralSchemaIncomingCfg = ViaductModulePluginSupport.setupIncomingConfigurationForCentralSchema(this)
            val generateResolverBasesTask = setupGenerateResolverBasesTask(moduleLayout, centralSchemaIncomingCfg)

            setupKspRegistryExtractor(moduleLayout, generateResolverBasesTask)

            ViaductModulePluginSupport.wireToContainingApplicationProject(
                this,
                ViaductPluginCommon.Configs.GRT_CLASSES_KOTLIN_INCOMING,
                ViaductPluginCommon.Configs.GRT_CLASSES_KOTLIN_OUTGOING,
            )

            // GRT classes into source sets
            plugins.withId("java") {
                configurations.named("implementation").configure { extendsFrom(grtIncomingCfg) }
                configurations.named("testImplementation").configure { extendsFrom(grtIncomingCfg) }
            }
            pluginManager.withPlugin("java-test-fixtures") {
                configurations.named("testFixturesImplementation").configure { extendsFrom(grtIncomingCfg) }
            }

            // Generated resolver bases into Kotlin source set
            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                val kotlinExt = extensions.getByType(KotlinJvmProjectExtension::class.java)
                kotlinExt.sourceSets.named("main") {
                    kotlin.srcDir(generateResolverBasesTask.flatMap { it.outputDirectory })
                }
            }

            configureIdeaIntegration(generateResolverBasesTask)

            // Convenience task for module-level codegen
            tasks.register("viaductCodegen") {
                description = "Run all Viaduct code generation for this module: compiles GRT classes and generates abstract resolver base classes."

                dependsOn(generateResolverBasesTask)
            }
        }

    private fun Project.setupKspRegistryExtractor(
        moduleLayout: ViaductModulePackageLayout,
        generateResolverBasesTask: TaskProvider<GenerateResolverBasesTask>,
    ) {
        val version = pluginVersion(ViaductModulePlugin::class.java)
        val codegenClasspath = createOrGetCodegenClasspath(version)

        // React when KSP is applied by the consumer — add the registry-extractor
        // processor and wire up the assembly task.
        pluginManager.withPlugin("com.google.devtools.ksp") {
            dependencies.add("ksp", "com.airbnb.viaduct:buildtime:$version")

            // KSP registers kspKotlin lazily, so tasks.named() would throw UnknownTaskException
            // here; tasks.matching() returns a live collection instead.
            // https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskCollection.html
            val kspKotlinTasks = tasks.matching { it.name == "kspKotlin" }

            // Fingerprints kspKotlin on the resolver-bases dir (RELATIVE = path-relative-to-root
            // + content, so cache still hits across checkouts).
            kspKotlinTasks.configureEach {
                inputs.files(generateResolverBasesTask.flatMap { it.outputDirectory })
                    .withPropertyName("viaductResolverBases")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            }

            // Bridge task: copies KSP's descriptor output to a stable intermediates directory.
            //
            // Two reasons for this indirection:
            // 1. KSP registers kspKotlin lazily, so the assembly task can't take a typed
            //    TaskProvider dependency on it; the string "dependsOn" below is the only option.
            //    The Sync wraps that into a typed provider that assembleTask can depend on safely.
            // 2. Gradle's Sync tracks its OutputDirectory, so when KSP removes a descriptor
            //    (because the upstream source file was deleted), the Sync re-runs and the
            //    deleted descriptor is not copied — propagating the deletion into intermediates.
            //
            // See impldocs/execution-registry-ksp-pipeline.md for the full pipeline explanation.
            val extractKspDescriptors = tasks.register<Sync>(
                "extractKspRegistryDescriptors"
            ) {
                from(layout.buildDirectory.dir("generated/ksp/main/resources/viaduct-registry"))
                into(layout.buildDirectory.dir("intermediates/viaduct-registry-descriptors"))
                dependsOn("kspKotlin")
            }

            val assembleTask = tasks.register<AssembleTenantModuleConfigFileTask>(
                "assembleViaductModuleConfigFile"
            ) {
                description = "Assembles the module's runtime resolver configuration from KSP-generated descriptors. Re-run after adding or modifying @Resolver-annotated classes."

                descriptorDir.set(layout.buildDirectory.dir("intermediates/viaduct-registry-descriptors"))
                executorFactory.set(AssembleTenantModuleConfigFileTask.EXECUTOR_FACTORY)
                this.codegenClasspath.from(codegenClasspath)
                outputDir.set(
                    project.layout.buildDirectory.dir("generated-resources/viaduct-registry")
                )

                dependsOn(extractKspDescriptors)
            }

            // Wire assembly output into main resources so it lands in the module's JAR
            pluginManager.withPlugin("java") {
                project.extensions
                    .getByType(JavaPluginExtension::class.java)
                    .sourceSets.named("main").configure {
                        resources.srcDir(assembleTask.flatMap { it.outputDir })
                    }
            }

            assembleTask.configure {
                tenantPackage.set(moduleLayout.fullTenantPackage)
                tenantPackagePrefix.set(moduleLayout.modulePackagePrefix)
            }
            // Same cache-key protection as above, but for the package identity values
            // rather than the resolver-bases file contents.
            kspKotlinTasks.configureEach {
                inputs.property("viaductTenantPackage", moduleLayout.fullTenantPackage)
                inputs.property("viaductTenantPackagePrefix", moduleLayout.modulePackagePrefix)
                inputs.property("viaductModulePackageSuffix", moduleLayout.modulePackageSuffix)
            }

            afterEvaluate {
                validateKspConfiguration()
            }
        }

        afterEvaluate {
            if (!plugins.hasPlugin("com.google.devtools.ksp")) {
                throw GradleException(
                    "Viaduct module '${project.displayName}' requires the KSP plugin but it is not applied.\n" +
                        "Add 'com.google.devtools.ksp' to your plugins block:\n" +
                        "  plugins {\n" +
                        "    id(\"com.google.devtools.ksp\") version \"<kotlin-version>-<ksp-version>\"\n" +
                        "  }\n" +
                        "See the Viaduct documentation for supported Kotlin and KSP versions."
                )
            }
        }
    }

    /**
     * We intentionally validate only the Kotlin plugin version that Viaduct supports.
     *
     * We can reliably detect whether `com.google.devtools.ksp` is applied, but Gradle's public
     * plugin APIs do not expose the resolved version of an applied plugin.
     *
     * We intentionally do not duplicate Kotlin/KSP's own compatibility checks here. They already
     * detect version mismatches and report them clearly, so this plugin only enforces Viaduct's
     * supported Kotlin range.
     */
    private fun Project.validateKspConfiguration() {
        val kotlinVersion = getKotlinPluginVersion()
        val error = validateKotlinVersion(kotlinVersion)
        if (error != null) throw GradleException(error)
    }

    internal companion object {
        private const val RESOLVER_CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.ViaductGenerator\$Main"

        fun validateKotlinVersion(kotlinVersion: String): String? {
            val major = kotlinVersion.substringBefore('.').toIntOrNull() ?: return null
            val minor = kotlinVersion.substringAfter('.').substringBefore('.').toIntOrNull() ?: return null

            return if (major < 1 || (major == 1 && minor < 9) || major > 2 || (major == 2 && minor > 2)) {
                "Viaduct requires Kotlin version in the range [1.9, 2.2] for KSP1 support. " +
                    "Found: $kotlinVersion. " +
                    "Kotlin 2.3+ requires KSP2 which is not yet supported by Viaduct."
            } else {
                null
            }
        }
    }

    private fun Project.getKotlinPluginVersion(): String {
        val kotlinExt = extensions.findByType(KotlinJvmProjectExtension::class.java)
            ?: throw GradleException("Kotlin JVM plugin must be applied before the Viaduct module plugin.")
        return kotlinExt.coreLibrariesVersion
    }

    private fun Project.setupGenerateResolverBasesTask(
        moduleLayout: ViaductModulePackageLayout,
        centralSchemaIncomingCfg: Configuration
    ): TaskProvider<GenerateResolverBasesTask> {
        val version = pluginVersion(ViaductModulePlugin::class.java)
        val codegenClasspath = createOrGetCodegenClasspath(version)
        return tasks.register<GenerateResolverBasesTask>("generateViaductResolverBases") {
            buildFlags.putAll(ViaductPluginCommon.DEFAULT_BUILD_FLAGS)
            centralSchemaFiles.from(
                centralSchemaIncomingCfg.incoming.artifactView {}.files.asFileTree.matching { include("**/*.graphqls") }
            )
            tenantFromSourceRegex.set("$centralSchemaDirectoryName/partition/(.*)/graphql")
            classpath.setFrom(codegenClasspath)
            mainClass.set(RESOLVER_CODEGEN_MAIN_CLASS)
            wireToModuleLayout(moduleLayout)
        }
    }
}
