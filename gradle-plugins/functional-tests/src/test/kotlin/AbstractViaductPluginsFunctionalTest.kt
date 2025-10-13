import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

abstract class AbstractViaductPluginsFunctionalTest {

    @field:TempDir
    protected lateinit var projectDir: File

    private val settingsFile by lazy {
        projectDir.resolve("settings.gradle.kts")
    }
    fun withSettingsFile(content: String) = settingsFile.writeText(content.trimIndent())
    fun withDefaultSettingsFile() {
        val repoUri = File("build/repo").toURI().toURL().toExternalForm()
        withSettingsFile(
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenLocal()
                }
            }
    
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                    mavenLocal()
                }
            }
        """.trimIndent()
        )
    }

    private val buildFile by lazy {
        projectDir.resolve("build.gradle.kts")
    }
    fun withBuildFile(content: String) =
        buildFile.writeText(content.trimIndent())

    private val schemaFile by lazy {
        val file = projectDir.resolve("src/main/viaduct/schema/schema.graphqls")
        file.parentFile.mkdirs()
        file
    }
    fun withSchemaFile(content: String) =
        schemaFile.writeText(content.trimIndent())

    fun withKotlinSourceFile(path: String, content: String) {
        val file = projectDir.resolve("src/main/kotlin/$path")
        file.parentFile.mkdirs()
        file.writeText(content.trimIndent())
    }

    protected fun runner(): GradleRunner {
        val runner = GradleRunner.create()
            .forwardOutput()
            .withProjectDir(projectDir)
        return runner
    }

    protected val viaductVersion = System.getProperty("viaduct.version")

    protected companion object {
        @JvmStatic
        @Suppress("MagicNumber")
        fun getGradleTestVersions(): List<String> {
            return GradleTestVersions.getVersions()
        }
    }
}