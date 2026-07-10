package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ViaductSettingsPluginFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    @Test
    fun `settings DSL includes projects and registers topology service`() {
        writeSettings(
            """
            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.app")

                includeModule {
                    project(":app:payments")
                    modulePackageSuffix("payments")
                }
            }
            """.trimIndent()
        )
        writeBuildScript(
            """
            tasks.register("printViaductTopology") {
                doLast {
                    val service = gradle.sharedServices.registrations
                        .named("ViaductTopologyService")
                        .get()
                        .service
                        .get()
                    val topology = service.javaClass
                        .getMethod("topologyFor", String::class.java)
                        .invoke(service, ":app:payments")
                    val prefix = topology.javaClass
                        .getMethod("getModulePackagePrefix")
                        .invoke(topology)
                    val suffixes = topology.javaClass
                        .getMethod("getModulePackageSuffixes")
                        .invoke(topology)
                    println("VIADUCT_PREFIX=${'$'}prefix")
                    println("VIADUCT_SUFFIXES=${'$'}suffixes")
                }
            }
            """.trimIndent()
        )

        val result = runner()
            .withArguments("projects", "printViaductTopology")
            .build()

        result.output shouldContain "Project ':app'"
        result.output shouldContain "Project ':app:payments'"
        result.output shouldContain "VIADUCT_PREFIX=com.example.app"
        result.output shouldContain ":app:payments=payments"
    }

    @Test
    fun `project descriptors can be customized inside topology declarations`() {
        writeSettings(
            """
            includeViaductApplication {
                project(":app").projectDir = file("custom-app")
                modulePackagePrefix("com.example.app")

                includeModule {
                    project(":app:payments").projectDir = file("custom-payments")
                    modulePackageSuffix("payments")
                }
            }
            """.trimIndent()
        )
        writeBuildScript("")

        val result = runner()
            .withArguments(":app:payments:help")
            .build()

        result.output shouldContain "BUILD SUCCESSFUL"
    }

    @Test
    fun `valid topology succeeds with configuration cache enabled`() {
        writeSettings(
            """
            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.app")

                includeModule {
                    project(":app:payments")
                    modulePackageSuffix("payments")
                }
            }
            """.trimIndent()
        )
        writeBuildScript("")

        val result = runner()
            .withArguments("help", "--configuration-cache", "--configuration-cache-problems=fail")
            .build()

        result.output shouldContain "BUILD SUCCESSFUL"
    }

    @Test
    fun `gradletestapps configure with settings plugin DSL`() {
        val result = GradleRunner.create()
            .withProjectDir(gradleTestAppsDir())
            .withArguments("help")
            .build()

        result.output shouldContain "BUILD SUCCESSFUL"
    }

    @Test
    fun `multiple disjoint applications and plain projects can coexist`() {
        writeSettings(
            """
            include(":support")

            includeViaductApplication {
                project(":app1")
                modulePackagePrefix("com.example.app1")

                includeModule {
                    project(":app1:inbox")
                    modulePackageSuffix("inbox")
                }
            }

            includeViaductApplication {
                project(":app2")
                modulePackagePrefix("com.example.app2")

                includeModule {
                    project(":app2:search")
                    modulePackageSuffix("search")
                }
            }
            """.trimIndent()
        )
        writeBuildScript(
            """
            tasks.register("printViaductTopologies") {
                doLast {
                    val service = gradle.sharedServices.registrations
                        .named("ViaductTopologyService")
                        .get()
                        .service
                        .get()
                    fun topologyFor(path: String): Any? =
                        service.javaClass
                            .getMethod("topologyFor", String::class.java)
                            .invoke(service, path)
                    fun prefixFor(path: String): Any? {
                        val topology = topologyFor(path)
                        return topology
                            ?.javaClass
                            ?.getMethod("getModulePackagePrefix")
                            ?.invoke(topology)
                    }

                    val app1Prefix = prefixFor(":app1:inbox")
                    val app2Prefix = prefixFor(":app2:search")
                    val supportTopology = topologyFor(":support")
                    println("APP1_PREFIX=${'$'}app1Prefix")
                    println("APP2_PREFIX=${'$'}app2Prefix")
                    println("SUPPORT_TOPOLOGY=${'$'}supportTopology")
                }
            }
            """.trimIndent()
        )

        val result = runner()
            .withArguments("projects", "printViaductTopologies")
            .build()

        result.output shouldContain "Project ':support'"
        result.output shouldContain "Project ':app1:inbox'"
        result.output shouldContain "Project ':app2:search'"
        result.output shouldContain "APP1_PREFIX=com.example.app1"
        result.output shouldContain "APP2_PREFIX=com.example.app2"
        result.output shouldContain "SUPPORT_TOPOLOGY=null"
    }

    @Test
    fun `application declaration without modules is valid`() {
        writeSettings(
            """
            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.app")
            }
            """.trimIndent()
        )
        writeBuildScript(
            """
            tasks.register("printApplicationTopology") {
                doLast {
                    val service = gradle.sharedServices.registrations
                        .named("ViaductTopologyService")
                        .get()
                        .service
                        .get()
                    val topology = service.javaClass
                        .getMethod("topologyFor", String::class.java)
                        .invoke(service, ":app")
                    val suffixes = topology.javaClass
                        .getMethod("getModulePackageSuffixes")
                        .invoke(topology)
                    println("APP_SUFFIXES=${'$'}suffixes")
                }
            }
            """.trimIndent()
        )

        val result = runner()
            .withArguments("projects", "printApplicationTopology")
            .build()

        result.output shouldContain "Project ':app'"
        result.output shouldContain "APP_SUFFIXES={}"
    }

    @Test
    fun `application project can be declared as the only module with an empty suffix`() {
        writeSettings(
            """
            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.app")

                includeModule {
                    project(":app")
                    modulePackageSuffix("")
                }
            }
            """.trimIndent()
        )
        writeBuildScript(
            """
            tasks.register("printSelfModuleTopology") {
                doLast {
                    val service = gradle.sharedServices.registrations
                        .named("ViaductTopologyService")
                        .get()
                        .service
                        .get()
                    val topology = service.javaClass
                        .getMethod("topologyFor", String::class.java)
                        .invoke(service, ":app")
                    val suffixes = topology.javaClass
                        .getMethod("getModulePackageSuffixes")
                        .invoke(topology) as Map<*, *>
                    val appPath = ":app"
                    println("APP_SUFFIX_PRESENT=${'$'}{suffixes.containsKey(appPath)}")
                    println("APP_SUFFIX=${'$'}{suffixes[appPath]}")
                }
            }
            """.trimIndent()
        )

        val result = runner()
            .withArguments("printSelfModuleTopology")
            .build()

        result.output shouldContain "APP_SUFFIX_PRESENT=true"
        result.output shouldContain "APP_SUFFIX="
        result.output shouldNotContain "APP_SUFFIX=null"
    }

    @Test
    fun `overlapping application roots fail during settings validation`() {
        writeSettings(
            """
            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.app")
            }
            includeViaductApplication {
                project(":app:nested")
                modulePackagePrefix("com.example.nested")
            }
            """.trimIndent()
        )
        writeBuildScript("")

        val result = runner()
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "[VIADUCT_APPLICATION_ROOT_OVERLAP]"
        result.output shouldContain ":app"
        result.output shouldContain ":app:nested"
    }

    @Test
    fun `missing required declarations fail during settings validation`() {
        writeSettings(
            """
            includeViaductApplication {
                includeModule {
                }
            }
            """.trimIndent()
        )
        writeBuildScript("")

        val result = runner()
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "[VIADUCT_APPLICATION_PROJECT_MISSING]"
        result.output shouldContain "[MODULE_PACKAGE_PREFIX_MISSING]"
        result.output shouldContain "[VIADUCT_MODULE_PROJECT_MISSING]"
        result.output shouldContain "[MODULE_PACKAGE_SUFFIX_MISSING]"
    }

    @Test
    fun `invalid package declarations and outside module paths fail during settings validation`() {
        writeSettings(
            """
            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example-app")

                includeModule {
                    project(":elsewhere:payments")
                    modulePackageSuffix("bad-suffix")
                }
            }
            """.trimIndent()
        )
        writeBuildScript("")

        val result = runner()
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "[MODULE_PACKAGE_PREFIX_INVALID]"
        result.output shouldContain "[VIADUCT_MODULE_OUTSIDE_APPLICATION]"
        result.output shouldContain "[MODULE_PACKAGE_SUFFIX_INVALID]"
    }

    @Test
    fun `duplicate module declarations fail during settings validation`() {
        writeSettings(
            """
            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.app")

                includeModule {
                    project(":app:payments")
                    modulePackageSuffix("payments")
                }
                includeModule {
                    project(":app:payments")
                    modulePackageSuffix("payments2")
                }
            }
            """.trimIndent()
        )
        writeBuildScript("")

        val result = runner()
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "[VIADUCT_MODULE_PROJECT_DUPLICATE]"
        result.output shouldContain ":app:payments"
    }

    @Test
    fun `duplicate suffixes fail with stable error code`() {
        writeSettings(
            """
            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.app")

                includeModule {
                    project(":app:alpha")
                    modulePackageSuffix("catalog")
                }
                includeModule {
                    project(":app:beta")
                    modulePackageSuffix("catalog")
                }
            }
            """.trimIndent()
        )
        writeBuildScript("")

        val result = runner()
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "[${ModuleSuffixValidator.DUPLICATE_SUFFIX}]"
        result.output shouldContain ":app:alpha"
        result.output shouldContain ":app:beta"
        result.output shouldContain "catalog"
    }

    @Test
    fun `prefix-colliding suffixes fail with stable error code`() {
        writeSettings(
            """
            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.app")

                includeModule {
                    project(":app:catalog")
                    modulePackageSuffix("catalog")
                }
                includeModule {
                    project(":app:pricing")
                    modulePackageSuffix("catalog.pricing")
                }
            }
            """.trimIndent()
        )
        writeBuildScript("")

        val result = runner()
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "[${ModuleSuffixValidator.PREFIX_COLLISION}]"
        result.output shouldContain ":app:catalog"
        result.output shouldContain ":app:pricing"
    }

    @Test
    fun `empty suffix with sibling modules fails with specific error`() {
        writeSettings(
            """
            includeViaductApplication {
                project(":app")
                modulePackagePrefix("com.example.app")

                includeModule {
                    project(":app")
                    modulePackageSuffix("")
                }
                includeModule {
                    project(":app:payments")
                    modulePackageSuffix("payments")
                }
            }
            """.trimIndent()
        )
        writeBuildScript("")

        val result = runner()
            .withArguments("help")
            .buildAndFail()

        result.output shouldContain "[${ModuleSuffixValidator.EMPTY_SUFFIX_WITH_SIBLINGS}]"
        result.output shouldContain "exactly one module"
        result.output shouldContain ":app"
    }

    private fun writeSettings(topologyBlock: String) {
        File(projectDir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                }
            }

            plugins {
                id("com.airbnb.viaduct.settings-gradle-plugin")
            }

            rootProject.name = "test"

            $topologyBlock
            """.trimIndent()
        )
    }

    private fun writeBuildScript(content: String) {
        File(projectDir, "build.gradle.kts").writeText(content)
    }

    private fun runner(): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()

    private fun gradleTestAppsDir(): File {
        val startDir = File(System.getProperty("user.dir"))
        return generateSequence(startDir) { it.parentFile }
            .flatMap { dir ->
                sequenceOf(
                    File(dir, "gradletestapps"),
                    File(dir, "gradle-plugins/gradletestapps"),
                )
            }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate gradletestapps from ${startDir.absolutePath}")
    }
}
