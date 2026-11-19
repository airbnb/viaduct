package conventions

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JdkSecurityTest {
    @Test
    fun `reject pre-fix releases and builds across every supported toolchain`() {
        listOf(
            "17.0.11+9-LTS",
            "17.0.21+11-LTS",
            "17.0.21.1+1",
            "21.0.4+7-LTS",
            "21.0.13+11-LTS",
            "21.0.13.1+1",
            "25.0.4+10-LTS",
            "25.0.5+8-LTS",
            "25.0.5.1+1"
        ).forEach { version ->
            val error = assertThrows(GradleException::class.java) {
                requirePatchedJdk(version, "/cached/jdk")
            }
            assertTrue(error.message!!.contains("/cached/jdk"))
            assertTrue(error.message!!.contains("install JDK"))
        }
    }

    @Test
    fun `accept patched Corretto and Zulu runtimes and later updates`() {
        listOf(
            "17.0.21.1+2-LTS",
            "21.0.13.1+2-LTS",
            "25.0.5.1+2-LTS",
            "17.0.21.1+3",
            "21.0.14+1-LTS",
            "25.0.6+1-LTS"
        ).forEach { version ->
            assertDoesNotThrow { requirePatchedJdk(version, "/patched/jdk") }
        }
    }

    @Test
    fun `reject release lines without a known supported security baseline`() {
        listOf("11.0.29+7", "18.0.2+9", "22.0.2+9", "24.0.2+12").forEach { version ->
            assertThrows(GradleException::class.java) {
                requirePatchedJdk(version, "/unsupported/jdk")
            }
        }
    }
}
