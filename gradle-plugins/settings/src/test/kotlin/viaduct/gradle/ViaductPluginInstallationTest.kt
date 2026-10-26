package viaduct.gradle

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.initialization.Settings
import org.gradle.api.plugins.JavaPlugin
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ViaductPluginInstallationTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `capabilities and stubs are application scoped and reuse the isolated configuration cache`() {
        settings(
            """
            includeViaductApplication {
                usePlugin("test.viaduct.capability")
                usePlugin("test.viaduct.capability")
                project(":first")
                modulePackagePrefix("example.first")
                includeModule { project(":first:tenant"); modulePackageSuffix("tenant") }
            }
            includeViaductApplication {
                project(":plain")
                modulePackagePrefix("example.plain")
                includeModule { project(":plain:tenant"); modulePackageSuffix("tenant") }
            }
            includeViaductApplication {
                usePlugin("test.viaduct.capability")
                project(":second")
                modulePackagePrefix("example.second")
                includeModule { project(":second:tenant"); modulePackageSuffix("tenant") }
            }
            """.trimIndent(),
        )
        File(directory, "first/tenant/build.gradle.kts").apply {
            parentFile.mkdirs()
            writeText("plugins { id(\"test.viaduct.module\") }")
        }
        listOf("plain/tenant", "second/tenant").forEach { File(directory, it).mkdirs() }

        val first = runner().build()
        first.output shouldContain "CAPABILITY :first example.first"
        first.output shouldContain "CAPABILITY :second example.second"
        first.output shouldContain "STUB :first:tenant"
        first.output shouldContain "STUB :second:tenant"
        first.output shouldContain "REAL :first:tenant"
        first.output shouldNotContain "CAPABILITY :plain"
        first.output shouldNotContain "STUB :plain:tenant"

        runner().build().output shouldContain "Reusing configuration cache"
    }

    @Test
    fun `a settings plugin must register before its apply method returns`() {
        settings(application("test.viaduct.missing"))
        runner().buildAndFail().output shouldContain "did not call registerViaductPlugin before apply returned"
    }

    @Test
    fun `conflicting registrations identify the plugin`() {
        settings(application("test.viaduct.conflict"))
        runner().buildAndFail().output shouldContain "Conflicting Viaduct plugin registrations for 'test.viaduct.conflict'"
    }

    @Test
    fun `an unavailable settings plugin fails with its requested id`() {
        settings(application("test.viaduct.unavailable"))
        runner().buildAndFail().output shouldContain "test.viaduct.unavailable"
    }

    private fun application(id: String) =
        """
        includeViaductApplication {
            usePlugin("$id")
            project(":")
            modulePackagePrefix("example.app")
        }
        """.trimIndent()

    private fun settings(declarations: String) {
        File(directory, "settings.gradle.kts").writeText(
            """
            plugins { id("com.airbnb.viaduct.settings-gradle-plugin") }
            rootProject.name = "installation-test"
            $declarations
            """.trimIndent(),
        )
        File(directory, "build.gradle.kts").writeText("")
    }

    private fun runner(): GradleRunner {
        val descriptors = File(directory, "fixture-plugins/META-INF/gradle-plugins").apply { mkdirs() }
        mapOf(
            "test.viaduct.capability" to FixtureCapabilitySettingsPlugin::class.java,
            "test.viaduct.missing" to FixtureUnregisteredSettingsPlugin::class.java,
            "test.viaduct.conflict" to FixtureConflictingSettingsPlugin::class.java,
            "test.viaduct.module" to FixtureModulePlugin::class.java,
        ).forEach { (id, implementation) ->
            File(descriptors, "$id.properties").writeText("implementation-class=${implementation.name}\n")
        }
        val runner = GradleRunner.create().withPluginClasspath()
        return runner.withProjectDir(directory)
            .withPluginClasspath(
                runner.pluginClasspath +
                    File(FixtureCapabilitySettingsPlugin::class.java.protectionDomain.codeSource.location.toURI()) +
                    descriptors.parentFile.parentFile,
            )
            .withArguments("help", "--configuration-cache", "--configuration-cache-problems=fail", "-Dorg.gradle.unsafe.isolated-projects=true", "--parallel", "--max-workers=2")
    }
}

class FixtureCapabilitySettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        repeat(2) {
            settings.registerViaductPlugin("test.viaduct.capability", FixtureApplicationPlugin::class.java, FixtureModuleStubPlugin::class.java)
        }
    }
}

class FixtureUnregisteredSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) = Unit
}

class FixtureConflictingSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.registerViaductPlugin("test.viaduct.conflict", FixtureApplicationPlugin::class.java)
        settings.registerViaductPlugin("test.viaduct.conflict", JavaPlugin::class.java)
    }
}

class FixtureApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val conventions = project.extensions.getByType(ViaductApplicationConventions::class.java)
        project.configurations.resolvable("fixtureInputs") {
            extendsFrom(conventions.modules.get())
            isTransitive = false
            attributes {
                attribute(ViaductArtifacts.KIND, "test.viaduct.inputs")
                attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, ViaductArtifacts.USAGE))
            }
        }
        println("CAPABILITY ${project.path} ${conventions.modulePackagePrefix}")
    }
}

class FixtureModuleStubPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.getByType(ViaductModuleConventions::class.java)
        project.configurations.consumable("fixtureInputs") {
            attributes {
                attribute(ViaductArtifacts.KIND, "test.viaduct.inputs")
                attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, ViaductArtifacts.USAGE))
            }
        }
        println("STUB ${project.path}")
    }
}

class FixtureModulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(FixtureModuleStubPlugin::class.java)
        project.configurations.named("fixtureInputs") {
            outgoing.artifact(project.layout.projectDirectory.file("input.txt"))
        }
        println("REAL ${project.path}")
    }
}
