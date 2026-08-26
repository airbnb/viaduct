package viaduct.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.java.runtime.bootstrap.ViaductJavaExecutorFactory

class ViaductJavaModulePluginFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    private fun combinedPluginClasspath(): List<File> =
        System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it) }

    @Test
    fun `Java GRT generation and runtime use the same fixed package`() {
        assertEquals(
            ViaductPluginCommon.JAVA_GRT_PACKAGE,
            ViaductJavaExecutorFactory.JAVA_GRT_PACKAGE_PREFIX,
        )
    }

    @Test
    fun `java module plugin without settings topology fails with clear message`() {
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
                id("com.airbnb.viaduct.module-java-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("help")
            .buildAndFail()

        assertTrue(result.output.contains("Viaduct settings topology"), "Expected output to mention Viaduct settings topology")
        assertTrue(result.output.contains("settings-gradle-plugin"), "Expected output to mention 'settings-gradle-plugin'")
    }

    @Test
    fun `java module plugin rejects application-only topology placement`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }

            rootProject.name = "test"

            includeViaductApplication {
                project(":")
                modulePackagePrefix("com.example.test")
            }
            """.trimIndent()
        )
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.airbnb.viaduct.application-gradle-plugin")
                id("com.airbnb.viaduct.module-java-gradle-plugin")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments("help")
            .buildAndFail()

        assertTrue(result.output.contains("not as a module project"), "Expected output to reject application-only placement")
    }

    @Test
    fun `Java module resolves schema and Java grt dependencies from settings application path`() {
        writeJavaApplicationAndModuleFixture()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":app:javaresolvers:printViaductJavaApplicationAnchor")
            .build()

        assertTrue(result.output.contains("VIADUCT_APPLICATION_PROJECT=:app"), result.output)
        assertTrue(result.output.contains("VIADUCT_APPLICATION_CONFIGURATION=null"), result.output)
        assertTrue(result.output.contains("CENTRAL_SCHEMA_EXTENDS=[viaductApplication]"), result.output)
        assertTrue(result.output.contains("JAVA_GRT_EXTENDS=[]"), result.output)
        assertTrue(result.output.contains("DIRECT_CENTRAL_SCHEMA_DEPS=[]"), result.output)
        assertTrue(result.output.contains("DIRECT_JAVA_GRT_DEPS=[:app]"), result.output)
        assertTrue(
            result.output.contains("DIRECT_JAVA_GRT_CONFIGURATIONS=[viaductJavaGRTClasses]"),
            result.output
        )
        assertTrue(result.output.contains("CENTRAL_SCHEMA_KIND=central-schema"), result.output)
        assertTrue(result.output.contains("JAVA_GRT_KIND=java-grt-classes"), result.output)
    }

    @Test
    fun `Java module configures under isolated projects with configuration cache`() {
        writeJavaApplicationAndModuleFixture()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(
                ":app:javaresolvers:help",
                "--configuration-cache",
                "--configuration-cache-problems=fail",
                "-Dorg.gradle.unsafe.isolated-projects=true",
            )
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `topology package values configure Java module resolver base task without project DSL`() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }

            rootProject.name = "test"

            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.topology")

                includeModule {
                    project(":app:javaresolvers")
                    modulePackageSuffix("javaresolvers")
                }
            }
            """.trimIndent()
        )
        File(projectDir, "build.gradle").writeText("")

        val appDir = File(projectDir, "app").also { it.mkdirs() }
        File(appDir, "build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'com.airbnb.viaduct.application-gradle-plugin'
            }
            """.trimIndent()
        )

        val moduleDir = File(projectDir, "app/javaresolvers").also { it.mkdirs() }
        File(moduleDir, "build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'com.airbnb.viaduct.module-java-gradle-plugin'
            }

            tasks.register('printViaductJavaTopologyPackageInputs') {
                doLast {
                    def resolverTask = tasks.named('generateViaductResolverBases').get()

                    println "JAVA_TENANT_PREFIX=${'$'}{resolverTask.tenantPackagePrefix.get()}"
                    println "JAVA_TENANT_PACKAGE=${'$'}{resolverTask.tenantPackage.get()}"
                    println "JAVA_OUTPUT=${'$'}{resolverTask.outputDirectory.get().asFile.absolutePath.replace(File.separatorChar, '/' as char)}"
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":app:javaresolvers:printViaductJavaTopologyPackageInputs")
            .build()

        assertTrue(result.output.contains("JAVA_TENANT_PREFIX=com.example.topology"))
        assertTrue(result.output.contains("JAVA_TENANT_PACKAGE=javaresolvers"))
        assertTrue(
            result.output.contains("generated-sources/viaduct/javaResolverBases"),
            "Expected Java resolver-base output root to be configured",
        )
    }

    @Test
    fun `Java module configures file based registry pipeline`() {
        writeJavaApplicationAndModuleFixture()
        val moduleBuild = File(projectDir, "app/javaresolvers/build.gradle")
        moduleBuild.appendText(
            """

            tasks.register('printJavaRegistryPipeline') {
                doLast {
                    def annotationProcessors =
                        configurations.getByName('annotationProcessor').dependencies
                            .collect { "${'$'}{it.group}:${'$'}{it.name}" }
                    def resourceDirs =
                        project.extensions.getByType(org.gradle.api.plugins.JavaPluginExtension)
                            .sourceSets.getByName('main').resources.srcDirs
                            .collect { it.absolutePath.replace(File.separatorChar, '/' as char) }
                    def codegenDependencies =
                        tasks.named('viaductCodegen').get().taskDependencies
                            .getDependencies(tasks.named('viaductCodegen').get())
                            .collect { it.name }
                    def jarExcludes = tasks.named('jar').get().excludes
                    def apiName =
                        tasks.named('assembleViaductModuleConfigFile').get().apiName.get()

                    println "HAS_ASSEMBLE_TASK=${'$'}{tasks.names.contains('assembleViaductModuleConfigFile')}"
                    println "JAVA_API_NAME=${'$'}apiName"
                    println "ANNOTATION_PROCESSORS=${'$'}annotationProcessors"
                    println "RESOURCE_DIRS=${'$'}resourceDirs"
                    println "CODEGEN_DEPENDS_ON_ASSEMBLY=${'$'}{codegenDependencies.contains('assembleViaductModuleConfigFile')}"
                    println "JAR_EXCLUDES_RAW_DESCRIPTORS=${'$'}{jarExcludes.contains('viaduct-registry/**')}"
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath(combinedPluginClasspath())
            .withArguments(":app:javaresolvers:printJavaRegistryPipeline")
            .build()

        assertTrue(result.output.contains("HAS_ASSEMBLE_TASK=true"), result.output)
        assertTrue(result.output.contains("JAVA_API_NAME=java"), result.output)
        assertTrue(
            result.output.contains("com.airbnb.viaduct:javaapi-buildtime"),
            result.output,
        )
        assertTrue(
            result.output.contains("generated-resources/viaduct-registry"),
            result.output,
        )
        assertTrue(result.output.contains("CODEGEN_DEPENDS_ON_ASSEMBLY=true"), result.output)
        assertTrue(result.output.contains("JAR_EXCLUDES_RAW_DESCRIPTORS=true"), result.output)
    }

    private fun writeJavaApplicationAndModuleFixture() {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }

            rootProject.name = "test"

            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.topology")

                includeModule {
                    project(":app:javaresolvers")
                    modulePackageSuffix("javaresolvers")
                }
            }
            """.trimIndent()
        )
        File(projectDir, "build.gradle").writeText("")

        val appDir = File(projectDir, "app").also { it.mkdirs() }
        File(appDir, "build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'com.airbnb.viaduct.application-gradle-plugin'
            }
            """.trimIndent()
        )

        val moduleDir = File(projectDir, "app/javaresolvers").also { it.mkdirs() }
        File(moduleDir, "build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'com.airbnb.viaduct.module-java-gradle-plugin'
            }

            tasks.register('printViaductJavaApplicationAnchor') {
                doLast {
                    def viaductApplicationDependency =
                        configurations.getByName('viaductApplication')
                            .dependencies
                            .findAll { it instanceof org.gradle.api.artifacts.ProjectDependency }
                            .first()
                    def centralSchemaExtends =
                        configurations.getByName('viaductCentralSchemaIn')
                            .extendsFrom
                            .collect { it.name }
                            .sort()
                    def grtExtends =
                        configurations.getByName('viaductJavaGRTClassesIn')
                            .extendsFrom
                            .collect { it.name }
                            .sort()
                    def directCentralSchemaDeps =
                        configurations.getByName('viaductCentralSchemaIn')
                            .dependencies
                            .findAll { it instanceof org.gradle.api.artifacts.ProjectDependency }
                            .collect { it.path }
                            .sort()
                    def directGrtDeps =
                        configurations.getByName('viaductJavaGRTClassesIn')
                            .dependencies
                            .findAll { it instanceof org.gradle.api.artifacts.ProjectDependency }
                            .collect { it.path }
                            .sort()
                    def directGrtConfigurations =
                        configurations.getByName('viaductJavaGRTClassesIn')
                            .dependencies
                            .findAll { it instanceof org.gradle.api.artifacts.ProjectDependency }
                            .collect { it.targetConfiguration }
                            .sort()
                    def viaductKindAttribute = org.gradle.api.attributes.Attribute.of('viaduct.kind', String)
                    def centralSchemaKind =
                        configurations.getByName('viaductCentralSchemaIn')
                            .attributes
                            .getAttribute(viaductKindAttribute)
                    def grtKind =
                        configurations.getByName('viaductJavaGRTClassesIn')
                            .attributes
                            .getAttribute(viaductKindAttribute)

                    println "VIADUCT_APPLICATION_PROJECT=${'$'}{viaductApplicationDependency.path}"
                    println "VIADUCT_APPLICATION_CONFIGURATION=${'$'}{viaductApplicationDependency.targetConfiguration}"
                    println "CENTRAL_SCHEMA_EXTENDS=${'$'}centralSchemaExtends"
                    println "JAVA_GRT_EXTENDS=${'$'}grtExtends"
                    println "DIRECT_CENTRAL_SCHEMA_DEPS=${'$'}directCentralSchemaDeps"
                    println "DIRECT_JAVA_GRT_DEPS=${'$'}directGrtDeps"
                    println "DIRECT_JAVA_GRT_CONFIGURATIONS=${'$'}directGrtConfigurations"
                    println "CENTRAL_SCHEMA_KIND=${'$'}centralSchemaKind"
                    println "JAVA_GRT_KIND=${'$'}grtKind"
                }
            }
            """.trimIndent()
        )
    }
}
