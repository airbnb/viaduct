package viaduct.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * TestKit functional tests for the module plugin.
 *
 * These tests cover diagnostics, configuration-time enforcement, and model wiring only.
 * Real execution (codegen, schema assembly) is validated through the gradletestapps fixtures.
 */
class ViaductModulePluginFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    /**
     * Returns the full test runtime classpath as the plugin classpath, which includes the module
     * plugin, application plugin, KSP, and all transitive dependencies.
     */
    private fun combinedPluginClasspath(): List<File> {
        return System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it) }
    }

    @Test
    fun `module plugin without settings topology fails with clear message`() {
        // Multi-project build: root applies no plugins; subproject applies only the module plugin.
        File(projectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "test"
            include("mymodule")
            """.trimIndent()
        )
        File(projectDir, "build.gradle.kts").writeText("")
        val moduleDir = File(projectDir, "mymodule").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.module-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help")
            .buildAndFail()

        assertTrue(result.output.contains("Viaduct settings topology"), "Expected output to mention Viaduct settings topology")
        assertTrue(result.output.contains("settings-gradle-plugin"), "Expected output to mention 'settings-gradle-plugin'")
    }

    @Test
    fun `same-project topology is supported`() {
        // Single-project build: both plugins applied to the root project (cli-starter topology).
        File(projectDir, "settings.gradle.kts").writeViaductSettings(modules = mapOf(":" to "test"))
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.application-gradle-plugin")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            viaductModule {
                modulePackageSuffix.set("test")
            }
            """.trimIndent()
        )
        val schemaDir = File(projectDir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaDir, "schema.graphqls").writeText("type Query { field: String }")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("assembleViaductCentralSchema", "--configuration-cache", "--configuration-cache-problems=fail", "-Dorg.gradle.unsafe.isolated-projects=true")
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), "Expected build to succeed")
        val partitionSchemaFile = File(projectDir, "build/viaduct/centralSchema/partition/test/graphql/schema.graphqls")
        assertTrue(partitionSchemaFile.isFile, "Expected same-project schema partition to be copied into central schema")
        assertTrue(partitionSchemaFile.readText().contains("field: String"))
    }

    @Test
    fun `topology package values configure Kotlin module tasks when project DSL disagrees`() {
        File(projectDir, "settings.gradle.kts").writeViaductSettings(
            applicationProjectPath = ":app",
            modulePackagePrefix = "com.example.topology",
            modules = mapOf(":app:mymodule" to "topology"),
        )
        File(projectDir, "build.gradle").writeText("")

        val appDir = File(projectDir, "app").also { it.mkdirs() }
        File(appDir, "build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'com.airbnb.viaduct.application-gradle-plugin'
            }
            viaductApplication {
                modulePackagePrefix.set('com.example.project')
            }
            """.trimIndent()
        )

        val moduleDir = File(projectDir, "app/mymodule").also { it.mkdirs() }
        File(moduleDir, "build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'org.jetbrains.kotlin.jvm'
                id 'com.airbnb.viaduct.module-gradle-plugin'
                id 'com.google.devtools.ksp'
            }
            viaductModule {
                modulePackageSuffix.set('project')
            }

            tasks.register('printViaductTopologyPackageInputs') {
                doLast {
                    def resolverTask = tasks.named('generateViaductResolverBases').get()
                    def partitionTask = tasks.named('prepareViaductSchemaPartition').get()

                    println "RESOLVER_TENANT_PREFIX=${'$'}{resolverTask.tenantPackagePrefix.get()}"
                    println "RESOLVER_TENANT_PACKAGE=${'$'}{resolverTask.tenantPackage.get()}"
                    println "RESOLVER_OUTPUT=${'$'}{resolverTask.outputDirectory.get().asFile.absolutePath.replace(File.separatorChar, '/' as char)}"
                    println "SCHEMA_PREFIX=${'$'}{partitionTask.prefixPath.get()}"
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":app:mymodule:printViaductTopologyPackageInputs")
            .build()

        assertTrue(result.output.contains("RESOLVER_TENANT_PREFIX=com.example.topology"))
        assertTrue(result.output.contains("RESOLVER_TENANT_PACKAGE=topology"))
        assertTrue(
            result.output.contains("generated-sources/viaduct/resolverBases/com/example/topology/topology"),
            "Expected resolver-base output path to use topology package values",
        )
        assertTrue(result.output.contains("SCHEMA_PREFIX=topology/graphql"))
    }

    @Test
    fun `blank suffix application module uses topology prefix for resolver package`() {
        File(projectDir, "settings.gradle.kts").writeViaductSettings(
            modulePackagePrefix = "com.example.blank",
            modules = mapOf(":" to ""),
        )
        File(projectDir, "build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'org.jetbrains.kotlin.jvm'
                id 'com.airbnb.viaduct.application-gradle-plugin'
                id 'com.airbnb.viaduct.module-gradle-plugin'
                id 'com.google.devtools.ksp'
            }

            tasks.register('printViaductBlankSuffixPackageInputs') {
                doLast {
                    def resolverTask = tasks.named('generateViaductResolverBases').get()
                    def partitionTask = tasks.named('prepareViaductSchemaPartition').get()

                    println "RESOLVER_TENANT_PREFIX='${'$'}{resolverTask.tenantPackagePrefix.get()}'"
                    println "RESOLVER_TENANT_PACKAGE=${'$'}{resolverTask.tenantPackage.get()}"
                    println "RESOLVER_OUTPUT=${'$'}{resolverTask.outputDirectory.get().asFile.absolutePath.replace(File.separatorChar, '/' as char)}"
                    println "SCHEMA_PREFIX=${'$'}{partitionTask.prefixPath.get()}"
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("printViaductBlankSuffixPackageInputs")
            .build()

        assertTrue(result.output.contains("RESOLVER_TENANT_PREFIX=''"))
        assertTrue(result.output.contains("RESOLVER_TENANT_PACKAGE=com.example.blank"))
        assertTrue(
            result.output.contains("generated-sources/viaduct/resolverBases/com/example/blank"),
            "Expected resolver-base output path to use topology package prefix",
        )
        assertTrue(result.output.contains("SCHEMA_PREFIX=graphql"))
    }

    @Test
    fun `module resolves schema and grt dependencies from settings application path`() {
        File(projectDir, "settings.gradle.kts").writeViaductSettings(
            applicationProjectPath = ":app",
            modules = mapOf(":app:mymodule" to "mymodule"),
        )
        File(projectDir, "build.gradle.kts").writeText("")

        val appDir = File(projectDir, "app").also { it.mkdirs() }
        File(appDir, "build.gradle.kts").writeText(
            """
            import org.gradle.api.artifacts.ProjectDependency

            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            """.trimIndent()
        )

        val moduleDir = File(projectDir, "app/mymodule").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            viaductModule {
                modulePackageSuffix.set("mymodule")
            }

            tasks.register("printViaductApplicationAnchor") {
                doLast {
                    val projectDependencyType = ProjectDependency::class.java
                    val viaductApplicationDependency =
                        configurations.getByName("viaductApplication")
                            .dependencies
                            .withType(projectDependencyType)
                            .single()
                    val centralSchemaExtends =
                        configurations.getByName("viaductCentralSchemaIn")
                            .extendsFrom
                            .map { it.name }
                            .sorted()
                    val grtExtends =
                        configurations.getByName("viaductKotlinGRTClassesIn")
                            .extendsFrom
                            .map { it.name }
                            .sorted()
                    val directCentralSchemaDeps =
                        configurations.getByName("viaductCentralSchemaIn")
                            .dependencies
                            .withType(projectDependencyType)
                            .map { it.path }
                            .sorted()
                    val directGrtDeps =
                        configurations.getByName("viaductKotlinGRTClassesIn")
                            .dependencies
                            .withType(projectDependencyType)
                            .map { it.path }
                            .sorted()
                    val directGrtConfigurations =
                        configurations.getByName("viaductKotlinGRTClassesIn")
                            .dependencies
                            .withType(projectDependencyType)
                            .mapNotNull { it.targetConfiguration }
                            .sorted()
                    val viaductKindAttribute = org.gradle.api.attributes.Attribute.of("viaduct.kind", String::class.java)
                    val centralSchemaKind =
                        configurations.getByName("viaductCentralSchemaIn")
                            .attributes
                            .getAttribute(viaductKindAttribute)
                    val grtKind =
                        configurations.getByName("viaductKotlinGRTClassesIn")
                            .attributes
                            .getAttribute(viaductKindAttribute)

                    println("VIADUCT_APPLICATION_PROJECT=${'$'}{viaductApplicationDependency.path}")
                    println("VIADUCT_APPLICATION_CONFIGURATION=${'$'}{viaductApplicationDependency.targetConfiguration}")
                    println("CENTRAL_SCHEMA_EXTENDS=${'$'}centralSchemaExtends")
                    println("KOTLIN_GRT_EXTENDS=${'$'}grtExtends")
                    println("DIRECT_CENTRAL_SCHEMA_DEPS=${'$'}directCentralSchemaDeps")
                    println("DIRECT_KOTLIN_GRT_DEPS=${'$'}directGrtDeps")
                    println("DIRECT_KOTLIN_GRT_CONFIGURATIONS=${'$'}directGrtConfigurations")
                    println("CENTRAL_SCHEMA_KIND=${'$'}centralSchemaKind")
                    println("KOTLIN_GRT_KIND=${'$'}grtKind")
                }
            }
            """.trimIndent()
        )
        val schemaDir = File(moduleDir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaDir, "schema.graphqls").writeText("type Query { field: String }")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":app:mymodule:printViaductApplicationAnchor")
            .build()

        assertTrue(result.output.contains("VIADUCT_APPLICATION_PROJECT=:app"), "Expected viaductApplication dependency to target ':app'")
        assertTrue(result.output.contains("VIADUCT_APPLICATION_CONFIGURATION=null"), result.output)
        assertTrue(result.output.contains("CENTRAL_SCHEMA_EXTENDS=[viaductApplication]"), result.output)
        assertTrue(result.output.contains("KOTLIN_GRT_EXTENDS=[]"), result.output)
        assertTrue(result.output.contains("DIRECT_CENTRAL_SCHEMA_DEPS=[]"), result.output)
        assertTrue(result.output.contains("DIRECT_KOTLIN_GRT_DEPS=[:app]"), result.output)
        assertTrue(
            result.output.contains("DIRECT_KOTLIN_GRT_CONFIGURATIONS=[viaductKotlinGRTClasses]"),
            result.output
        )
        assertTrue(result.output.contains("CENTRAL_SCHEMA_KIND=central-schema"), result.output)
        assertTrue(result.output.contains("KOTLIN_GRT_KIND=kotlin-grt-classes"), result.output)
    }

    @Test
    fun `same-project module does not add self dependency to viaductApplication bucket`() {
        File(projectDir, "settings.gradle.kts").writeViaductSettings(modules = mapOf(":" to "test"))
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.application-gradle-plugin")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }

            tasks.register("printSelfContainedViaductApplicationWiring") {
                doLast {
                    val projectDependencyType = org.gradle.api.artifacts.ProjectDependency::class.java
                    val selfProjectDeps =
                        configurations.getByName("viaductApplication")
                            .dependencies
                            .withType(projectDependencyType)
                            .map { it.path }
                            .sorted()
                    val centralSchemaProjectDeps =
                        configurations.getByName("viaductCentralSchemaIn")
                            .dependencies
                            .withType(projectDependencyType)
                            .map { it.path }
                            .sorted()
                    val grtProjectDeps =
                        configurations.getByName("viaductKotlinGRTClassesIn")
                            .dependencies
                            .withType(projectDependencyType)
                            .map { it.path }
                            .sorted()
                    val centralSchemaFileDeps =
                        configurations.getByName("viaductCentralSchemaIn")
                            .dependencies
                            .filterIsInstance<org.gradle.api.artifacts.FileCollectionDependency>()
                            .size
                    val grtFileDeps =
                        configurations.getByName("viaductKotlinGRTClassesIn")
                            .dependencies
                            .filterIsInstance<org.gradle.api.artifacts.FileCollectionDependency>()
                            .size

                    println("VIADUCT_APPLICATION_PROJECT_DEPS=${'$'}selfProjectDeps")
                    println("CENTRAL_SCHEMA_PROJECT_DEPS=${'$'}centralSchemaProjectDeps")
                    println("KOTLIN_GRT_PROJECT_DEPS=${'$'}grtProjectDeps")
                    println("CENTRAL_SCHEMA_FILE_DEP_COUNT=${'$'}centralSchemaFileDeps")
                    println("KOTLIN_GRT_FILE_DEP_COUNT=${'$'}grtFileDeps")
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("printSelfContainedViaductApplicationWiring")
            .build()

        assertTrue(result.output.contains("VIADUCT_APPLICATION_PROJECT_DEPS=[]"), result.output)
        assertTrue(result.output.contains("CENTRAL_SCHEMA_PROJECT_DEPS=[]"), result.output)
        assertTrue(result.output.contains("KOTLIN_GRT_PROJECT_DEPS=[]"), result.output)
        assertTrue(result.output.contains("CENTRAL_SCHEMA_FILE_DEP_COUNT=1"), result.output)
        assertTrue(result.output.contains("KOTLIN_GRT_FILE_DEP_COUNT=1"), result.output)
    }

    @Test
    fun `application viaductModules bucket includes only topology modules for disjoint application roots`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }

            rootProject.name = "test"
            include("support")

            includeViaductApplication {
                project(":apps:one")
                modulePackagePrefix("com.example.one")

                includeModule {
                    project(":apps:one:modules:one")
                    modulePackageSuffix("one")
                }
            }

            includeViaductApplication {
                project(":apps:two")
                modulePackagePrefix("com.example.two")

                includeModule {
                    project(":apps:two:modules:two")
                    modulePackageSuffix("two")
                }
            }
            """.trimIndent()
        )
        File(projectDir, "build.gradle.kts").writeText("")
        File(projectDir, "support").mkdirs()
        File(projectDir, "support/build.gradle.kts").writeText("plugins { `java-library` }")

        listOf("one", "two").forEach { name ->
            val appDir = File(projectDir, "apps/$name").also { it.mkdirs() }
            File(appDir, "build.gradle.kts").writeText(
                """
                import org.gradle.api.artifacts.ProjectDependency

                plugins {
                    `java-library`
                    id("com.airbnb.viaduct.application-gradle-plugin")
                }

                tasks.register("printViaductTopologyDependencies") {
                    doLast {
                        val projectDependencyType = ProjectDependency::class.java
                        val viaductModuleDeps =
                            configurations.getByName("viaductModules")
                                .dependencies
                                .withType(projectDependencyType)
                                .map { it.path }
                                .sorted()
                        val schemaExtends =
                            configurations.getByName("viaductAllSchemaPartitionsIn")
                                .extendsFrom
                                .map { it.name }
                                .sorted()
                        val runtimeExtends =
                            configurations.getByName("runtimeOnly")
                                .extendsFrom
                                .map { it.name }
                                .sorted()
                        val directSchemaDeps =
                            configurations.getByName("viaductAllSchemaPartitionsIn")
                                .dependencies
                                .withType(projectDependencyType)
                                .map { it.path }
                                .sorted()
                        val directRuntimeDeps =
                            configurations.getByName("runtimeOnly")
                                .dependencies
                                .withType(projectDependencyType)
                                .map { it.path }
                                .sorted()

                        println("VIADUCT_MODULE_DEPS=${'$'}viaductModuleDeps")
                        println("SCHEMA_EXTENDS=${'$'}schemaExtends")
                        println("RUNTIME_EXTENDS=${'$'}runtimeExtends")
                        println("DIRECT_SCHEMA_DEPS=${'$'}directSchemaDeps")
                        println("DIRECT_RUNTIME_DEPS=${'$'}directRuntimeDeps")
                    }
                }
                """.trimIndent()
            )

            val moduleDir = File(projectDir, "apps/$name/modules/$name").also { it.mkdirs() }
            File(moduleDir, "build.gradle.kts").writeText(
                """
                plugins {
                    `java-library`
                    kotlin("jvm")
                    id("com.airbnb.viaduct.module-gradle-plugin")
                    id("com.google.devtools.ksp")
                }
                """.trimIndent()
            )
        }

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":apps:one:printViaductTopologyDependencies")
            .build()

        assertTrue(result.output.contains("VIADUCT_MODULE_DEPS=[:apps:one:modules:one]"), result.output)
        assertTrue(result.output.contains("SCHEMA_EXTENDS=[viaductModules]"), result.output)
        assertTrue(result.output.contains("RUNTIME_EXTENDS=[viaductModules]"), result.output)
        assertTrue(result.output.contains("DIRECT_SCHEMA_DEPS=[]"), result.output)
        assertTrue(result.output.contains("DIRECT_RUNTIME_DEPS=[]"), result.output)
        assertTrue(!result.output.contains(":apps:two:modules:two"), "Expected :apps:one not to wire :apps:two:modules:two")
        assertTrue(!result.output.contains(":support"), "Expected plain included support project not to be wired")
    }

    @Test
    fun `module without schema directory fails with clear message`() {
        File(projectDir, "settings.gradle.kts").writeViaductSettings(modules = mapOf(":mymodule" to "test"))
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            """.trimIndent()
        )
        val moduleDir = File(projectDir, "mymodule").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            viaductModule {
                modulePackageSuffix.set("test")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":mymodule:prepareViaductSchemaPartition")
            .buildAndFail()

        assertTrue(result.output.contains("src/main/viaduct/schema"), "Expected output to mention 'src/main/viaduct/schema'")
        assertTrue(result.output.contains("graphqls"), "Expected output to mention 'graphqls'")
    }

    @Test
    fun `direct dependency on module from another application root fails with clear message`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }

            rootProject.name = "test"

            includeViaductApplication {
                project(":apps:one")
                modulePackagePrefix("com.example.one")

                includeModule {
                    project(":apps:one:modules:one")
                    modulePackageSuffix("one")
                }
            }

            includeViaductApplication {
                project(":apps:two")
                modulePackagePrefix("com.example.two")

                includeModule {
                    project(":apps:two:modules:two")
                    modulePackageSuffix("two")
                }
            }
            """.trimIndent()
        )
        File(projectDir, "build.gradle.kts").writeText("")

        listOf("one", "two").forEach { name ->
            val appDir = File(projectDir, "apps/$name").also { it.mkdirs() }
            File(appDir, "build.gradle.kts").writeText(
                """
                plugins {
                    `java-library`
                    id("com.airbnb.viaduct.application-gradle-plugin")
                }
                """.trimIndent()
            )
        }

        val moduleOneDir = File(projectDir, "apps/one/modules/one").also { it.mkdirs() }
        File(moduleOneDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }

            dependencies {
                implementation(project(":apps:two:modules:two"))
            }
            """.trimIndent()
        )

        val moduleTwoDir = File(projectDir, "apps/two/modules/two").also { it.mkdirs() }
        File(moduleTwoDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":apps:one:modules:one:dependencies", "--configuration", "runtimeClasspath")
            .buildAndFail()

        assertTrue(
            result.output.contains("Module :apps:one:modules:one must not depend directly on :apps:two:modules:two"),
            "Expected output to reject the cross-application direct module dependency",
        )
    }

    @Test
    fun `direct module-to-module dependency fails with clear message`() {
        File(projectDir, "settings.gradle.kts").writeViaductSettings(
            modules = mapOf(
                ":moduleA" to "moduleA",
                ":moduleB" to "moduleB",
            ),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            """.trimIndent()
        )
        val moduleADir = File(projectDir, "moduleA").also { it.mkdirs() }
        File(moduleADir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            dependencies {
                implementation(project(":moduleB"))
            }
            """.trimIndent()
        )
        val schemaADir = File(moduleADir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaADir, "schema.graphqls").writeText("type Query { fieldA: String }")

        val moduleBDir = File(projectDir, "moduleB").also { it.mkdirs() }
        File(moduleBDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            """.trimIndent()
        )
        val schemaBDir = File(moduleBDir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaBDir, "schema.graphqls").writeText("type Query { fieldB: String }")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":moduleA:dependencies")
            .buildAndFail()

        assertTrue(result.output.contains(":moduleA"), "Expected output to mention ':moduleA'")
        assertTrue(result.output.contains(":moduleB"), "Expected output to mention ':moduleB'")
        assertTrue(result.output.contains("central schema"), "Expected output to mention 'central schema'")
    }

    @Test
    fun `dependency on non-module project is allowed`() {
        File(projectDir, "settings.gradle.kts").writeViaductSettings(
            modules = mapOf(":moduleA" to "moduleA"),
            plainIncludes = listOf(":libproject"),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            """.trimIndent()
        )
        val moduleADir = File(projectDir, "moduleA").also { it.mkdirs() }
        File(moduleADir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            dependencies {
                implementation(project(":libproject"))
            }
            """.trimIndent()
        )
        val schemaADir = File(moduleADir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaADir, "schema.graphqls").writeText("type Query { fieldA: String }")

        val libDir = File(projectDir, "libproject").also { it.mkdirs() }
        File(libDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":moduleA:dependencies")
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), "Expected build to succeed")
    }

    @Test
    fun `dependency on non-module project with same leaf name as another module is not blocked`() {
        File(projectDir, "settings.gradle.kts").writeViaductSettings(
            modules = mapOf(":module" to "module"),
            plainIncludes = listOf(":lib:module"),
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            """.trimIndent()
        )
        val moduleDir = File(projectDir, "module").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            dependencies {
                implementation(project(":lib:module"))
            }
            """.trimIndent()
        )
        val schemaDir = File(moduleDir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaDir, "schema.graphqls").writeText("type Query { field: String }")

        val libModuleDir = File(projectDir, "lib/module").also { it.mkdirs() }
        File(libModuleDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":module:dependencies")
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), "Expected build to succeed")
    }

    @Test
    fun `assembleViaductCentralSchema succeeds when optional schema directories are absent`() {
        File(projectDir, "settings.gradle.kts").writeViaductSettings(modules = mapOf(":mymodule" to "mymodule"))
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            """.trimIndent()
        )
        val moduleDir = File(projectDir, "mymodule").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            viaductModule {
                modulePackageSuffix.set("mymodule")
            }
            """.trimIndent()
        )
        val schemaDir = File(moduleDir, "src/main/viaduct/schema").also { it.mkdirs() }
        File(schemaDir, "schema.graphqls").writeText("type Query { field: String }")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("assembleViaductCentralSchema")
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"), "Expected build to succeed")
    }

    @Test
    fun `schema directory with no graphqls files fails with clear message`() {
        File(projectDir, "settings.gradle.kts").writeViaductSettings(modules = mapOf(":mymodule" to "test"))
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
            }
            viaductApplication {
                modulePackagePrefix.set("com.example.test")
            }
            """.trimIndent()
        )
        val moduleDir = File(projectDir, "mymodule").also { it.mkdirs() }
        File(moduleDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm")
                id("com.airbnb.viaduct.module-gradle-plugin")
                id("com.google.devtools.ksp")
            }
            viaductModule {
                modulePackageSuffix.set("test")
            }
            """.trimIndent()
        )
        File(moduleDir, "src/main/viaduct/schema").mkdirs()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":mymodule:prepareViaductSchemaPartition")
            .buildAndFail()

        assertTrue(result.output.contains("src/main/viaduct/schema"), "Expected output to mention 'src/main/viaduct/schema'")
        assertTrue(result.output.contains("graphqls"), "Expected output to mention 'graphqls'")
    }
}
