package viaduct.tenant.codegen.graphql.schema

import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * B.16 :private-aliasing preservation guard.
 * The existing :private scope aliasing behaviour in ViaductSchemaExtensions.kt (lines 24-40)
 * is explicitly preserved in v1 (B.16 is deferred as a formal rule, but the existing
 * runtime behaviour must not be disturbed).
 * This test asserts that the key implementation strings from the aliasing block remain
 * present in the source file.
 */
class ScopeDeferredVerificationTest {

    // ── ViaductSchemaExtensions.kt :private aliasing block preserved ─

    @Test
    fun `ViaductSchemaExtensions includesScope private-aliasing check is preserved`() {
        // user.dir is the project directory for core:tenant:codegen in Gradle test execution.
        val sourceFile = File(
            System.getProperty("user.dir"),
            "src/main/kotlin/viaduct/tenant/codegen/graphql/schema/ViaductSchemaExtensions.kt"
        )
        assertTrue(sourceFile.exists(),
            "ViaductSchemaExtensions.kt must exist at expected path: ${sourceFile.absolutePath}")

        val content = sourceFile.readText()

        // Key strings from the :private aliasing block (lines 24-40).
        // If any of these are removed, the B.16 runtime behaviour is broken.
        assertTrue(content.contains("scope.endsWith(\":private\")"),
            "B.16: :private suffix check must remain in includesScope")
        assertTrue(content.contains("scope.removeSuffix(\":private\")"),
            "B.16: :private suffix removal must remain in includesScope")
        assertTrue(content.contains("setOf(scope, scope.removeSuffix"),
            "B.16: expanded private scope set construction must remain")
        assertTrue(content.contains("if (value in scopes || value == \"*\")"),
            "B.16: wildcard and scope membership check must remain")
    }

    @Test
    fun `ViaductSchemaExtensions isInScope public function still present`() {
        val sourceFile = File(
            System.getProperty("user.dir"),
            "src/main/kotlin/viaduct/tenant/codegen/graphql/schema/ViaductSchemaExtensions.kt"
        )
        val content = sourceFile.readText()
        assertTrue(content.contains("fun ViaductSchema.Def.isInScope(scope: String): Boolean"),
            "isInScope public function must remain in ViaductSchemaExtensions.kt")
    }
}
