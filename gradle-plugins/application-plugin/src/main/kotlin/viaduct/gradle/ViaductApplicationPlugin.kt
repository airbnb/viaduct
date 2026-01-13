package viaduct.gradle

import centralSchemaDirectory
import grtClassesDirectory
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.register
import viaduct.gradle.ViaductPluginCommon.addViaductDependencies
import viaduct.gradle.ViaductPluginCommon.addViaductTestDependencies
import viaduct.gradle.ViaductPluginCommon.addViaductTestFixtures
import viaduct.gradle.ViaductPluginCommon.applyViaductBOM
import viaduct.gradle.ViaductPluginCommon.configureIdeaIntegration
import viaduct.gradle.task.AssembleCentralSchemaTask
import viaduct.gradle.task.GenerateGRTClassFilesTask

abstract class ViaductApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit =
        with(project) {
            require(this == rootProject) {
                "Apply 'com.airbnb.viaduct.application-gradle-plugin' only to the root project."
            }

            val appExt = extensions.create("viaductApplication", ViaductApplicationExtension::class.java, objects)

            // Set default BOM version to plugin version
            appExt.bomVersion.convention(ViaductPluginCommon.BOM.getDefaultVersion())

            val assembleCentralSchemaTask = setupAssembleCentralSchemaTask()
            setupOutgoingConfigurationForCentralSchema(assembleCentralSchemaTask)

            val generateGRTsTask = setupGenerateGRTsTask(appExt, assembleCentralSchemaTask)

            plugins.withId("java") {
                if (appExt.applyBOM.get()) {
                    applyViaductBOM(appExt.bomVersion.get())
                    addViaductDependencies(appExt.viaductDependencies.get())
                    addViaductTestDependencies(appExt.viaductTestDependencies.get())
                    addViaductTestFixtures(appExt.viaductTestFixtures.get())
                }
            }
            configureIdeaIntegration(generateGRTsTask)
            setupConsumableConfigurationForGRT(generateGRTsTask.flatMap { it.archiveFile })

            this.dependencies.add("api", files(generateGRTsTask.flatMap { it.archiveFile }))

            // Setup serve task
            setupServeTask(appExt, generateGRTsTask)
        }

    private fun Project.setupAssembleCentralSchemaTask(): TaskProvider<AssembleCentralSchemaTask> {
        val allPartitions = configurations.create(ViaductPluginCommon.Configs.ALL_SCHEMA_PARTITIONS_INCOMING).apply {
            description = "Resolvable configuration where all viaduct-module plugins send their schema partitions."
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes { attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.SCHEMA_PARTITION) }
        }

        val assembleCentralSchemaTask = tasks.register<AssembleCentralSchemaTask>("assembleViaductCentralSchema") {
            schemaPartitions.setFrom(allPartitions.incoming.artifactView {}.files)

            val baseSchemaDir = project.file("src/main/viaduct/schemabase")
            if (baseSchemaDir.exists()) {
                baseSchemaFiles.setFrom(
                    project.fileTree(baseSchemaDir) {
                        include("**/*.graphqls")
                    }
                )
            }

            outputDirectory.set(centralSchemaDirectory())
        }

        return assembleCentralSchemaTask
    }

    /** Call the bytecode-generator to generate GRT files. */
    private fun Project.setupGenerateGRTsTask(
        appExt: ViaductApplicationExtension,
        assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>,
    ): TaskProvider<Jar> {
        val pluginClasspath = files(ViaductPluginCommon.getClassPathElements(this@ViaductApplicationPlugin::class.java))

        val generateGRTClassesTask = tasks.register<GenerateGRTClassFilesTask>("generateViaductGRTClassFiles") {
            buildFlags.putAll(ViaductPluginCommon.DEFAULT_BUILD_FLAGS)
            grtClassesDirectory.set(grtClassesDirectory())
            schemaFiles.setFrom(assembleCentralSchemaTask.flatMap { it.outputDirectory.map { dir -> dir.asFileTree.matching { include("**/*.graphqls") }.files } })
            grtPackageName.set(appExt.grtPackageName)
            classpath.setFrom(pluginClasspath)
            mainClass.set(CODEGEN_MAIN_CLASS)
        }

        val generateGRTsTask = tasks.register<Jar>("generateViaductGRTs") {
            group = "viaduct"
            description = "Package GRT class files with the central schema."

            archiveBaseName.set("viaduct-grt")
            includeEmptyDirs = false

            from(generateGRTClassesTask.flatMap { it.grtClassesDirectory })

            from(assembleCentralSchemaTask.flatMap { it.outputDirectory }) {
                into("viaduct/centralSchema")
                exclude(BUILTIN_SCHEMA_FILE)
                includeEmptyDirs = false
            }
        }

        return generateGRTsTask
    }

    private fun Project.setupOutgoingConfigurationForCentralSchema(assembleCentralSchemaTask: TaskProvider<AssembleCentralSchemaTask>) {
        configurations.create(ViaductPluginCommon.Configs.CENTRAL_SCHEMA_OUTGOING).apply {
            description = """
              Consumable configuration consisting of a directory containing all schema fragments.  This directory
              is organized as a top-level file named $BUILTIN_SCHEMA_FILE, plus directories named "parition[/module-name]/graphql",
              where module-name is the modulePackageSuffix of the module with dots replaced by slashes (this segment is
              not present if the suffix is blank).
            """.trimIndent()
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes { attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.CENTRAL_SCHEMA) }
            outgoing.artifact(assembleCentralSchemaTask)
        }
    }

    private fun Project.setupConsumableConfigurationForGRT(artifact: Provider<RegularFile>) {
        configurations.create(ViaductPluginCommon.Configs.GRT_CLASSES_OUTGOING).apply {
            description =
                "Consumable configuration for the jar file containing the GRT classes plus the central schema's graphqls file."
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(ViaductPluginCommon.VIADUCT_KIND, ViaductPluginCommon.Kind.GRT_CLASSES)
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, LibraryElements.JAR)
                )
            }
            outgoing.artifact(artifact)
        }
    }

    private fun Project.setupServeTask(
        appExt: ViaductApplicationExtension,
        generateGRTsTask: TaskProvider<Jar>
    ) {
        // Capture configuration-time values for use in task (configuration cache safe)
        val isContinuousMode = gradle.startParameter.isContinuous
        // Allow property overrides, but default to extension values
        val servePort = project.findProperty("serve.port")?.toString()?.toIntOrNull() ?: appExt.servePort.get()
        val serveHost = project.findProperty("serve.host")?.toString() ?: appExt.serveHost.get()

        // Create configuration for serve runtime dependencies
        val serveConfig = configurations.create("serveRuntime") {
            isCanBeConsumed = false
            isCanBeResolved = true
            isVisible = false
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, LibraryElements.JAR)
                )
            }
        }

        // Add serve runtime dependencies
        val ktorVersion = "3.0.3"
        dependencies.add(serveConfig.name, "io.ktor:ktor-server-core:$ktorVersion")
        dependencies.add(serveConfig.name, "io.ktor:ktor-server-netty:$ktorVersion")
        dependencies.add(serveConfig.name, "io.ktor:ktor-server-content-negotiation:$ktorVersion")
        dependencies.add(serveConfig.name, "io.ktor:ktor-server-cors:$ktorVersion")
        dependencies.add(serveConfig.name, "io.ktor:ktor-server-websockets:$ktorVersion")
        dependencies.add(serveConfig.name, "io.ktor:ktor-serialization-jackson:$ktorVersion")
        dependencies.add(serveConfig.name, "com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")
        dependencies.add(serveConfig.name, "io.github.classgraph:classgraph:4.8.174")
        dependencies.add(serveConfig.name, "ch.qos.logback:logback-classic:1.4.14")

        // Get the application plugin's jar (contains bundled serve classes)
        val pluginClasspath = files(ViaductApplicationPlugin::class.java.protectionDomain.codeSource.location.toURI())

        tasks.register<org.gradle.api.tasks.JavaExec>("serve") {
            group = "viaduct"
            description = "Start the Viaduct development server with GraphiQL IDE. Use: ./gradlew --continuous serve"

            // Ensure GRTs are generated and classes are compiled before starting
            dependsOn(generateGRTsTask)
            dependsOn("classes")

            mainClass.set("viaduct.serve.ServeServerKt")

            // Configure classpath to include:
            // 1. Plugin jar (contains bundled serve classes)
            // 2. Serve runtime dependencies (Ktor, Jackson, etc.)
            // 3. App classes
            // 4. Runtime classpath (app dependencies)
            classpath = files(
                pluginClasspath,
                serveConfig,
                project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
                    .getByName("main").output,
                configurations.getByName("runtimeClasspath")
            )

            // Pass system properties for port, host, and package prefix from extension
            systemProperty("serve.port", servePort)
            systemProperty("serve.host", serveHost)

            // Use modulePackagePrefix from extension if set
            appExt.modulePackagePrefix.orNull?.let { packagePrefix ->
                systemProperty("serve.packagePrefix", packagePrefix)
            }

            // Enable standard I/O
            standardInput = System.`in`

            doFirst {
                logger.lifecycle("Starting Viaduct Development Server...")
                logger.lifecycle("GraphiQL IDE will be available at: http://$serveHost:$servePort/graphiql")
                if (!isContinuousMode) {
                    logger.lifecycle("")
                    logger.lifecycle("TIP: Run with --continuous flag for automatic reload on code changes:")
                    logger.lifecycle("     ./gradlew --continuous serve")
                }
            }
        }
    }

    companion object {
        private const val CODEGEN_MAIN_CLASS = "viaduct.tenant.codegen.cli.SchemaObjectsBytecode\$Main"
        const val BUILTIN_SCHEMA_FILE = "BUILTIN_SCHEMA.graphqls"
    }
}
