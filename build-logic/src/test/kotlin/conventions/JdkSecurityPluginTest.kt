package conventions

import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JdkSecurityPluginTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `compile and launch with a patched JDK and reuse the configuration cache`() {
        val feature = Runtime.version().feature()
        projectDir.resolve("settings.gradle").toFile().writeText("rootProject.name = 'jdk-security-test'\n")
        projectDir.resolve("build.gradle").toFile().writeText(
            """
            plugins {
                id 'org.jetbrains.kotlin.jvm'
                id 'conventions.jdk-security'
            }
            repositories { mavenCentral() }
            kotlin { jvmToolchain($feature) }
            tasks.register('runSmoke', JavaExec) {
                classpath = sourceSets.main.runtimeClasspath
                mainClass = 'Smoke'
            }
            """.trimIndent()
        )
        val source = projectDir.resolve("src/main/java/Smoke.java").toFile()
        source.parentFile.mkdirs()
        source.writeText(
            """
            public class Smoke {
                public static void main(String[] args) {
                    if (Runtime.version().feature() != $feature) throw new AssertionError(Runtime.version());
                    System.out.println("PATCHED_JDK=" + Runtime.version());
                }
            }
            """.trimIndent()
        )
        val runner = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("runSmoke", "--configuration-cache", "--stacktrace")
        val first = runner.build()
        assertTrue(first.output.contains("PATCHED_JDK="), first.output)
        val second = runner.build()
        assertTrue(second.output.contains("Reusing configuration cache."), second.output)
        assertTrue(second.output.contains("PATCHED_JDK="), second.output)
    }
}
