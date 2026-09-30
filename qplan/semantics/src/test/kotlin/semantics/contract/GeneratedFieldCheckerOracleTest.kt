package semantics.contract

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.correctresolution.CorrectnessCheckerObserver
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.correctResolution
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext

class GeneratedFieldCheckerOracleTest {
    @Test
    fun `an unregistered field cannot publish a non-null checker result`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { value: Int! }",
                fieldResolvers = { schema ->
                    val value = schema.requireObjectField("Query", "value")
                    mapOf(
                        value to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    )
                },
            ).assumptions
        val key =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "value"),
                emptyMap(),
            )
        val applications = CheckerApplicationRecorder()
        val operation =
            SharedOperationContext.create(
                world,
                resolverObserver = CorrectnessResolverObserver(),
                checkerObserver = CorrectnessCheckerObserver(applications),
            )
        val selections = world.fragmentFrom("fragment Query on Query { value }")

        listOf(CheckerResult.Success, Denial, null).forEach { stored ->
            val result = ObjectEngineResult.of(
                type = world.schema.requireQueryTypeDef(),
                values = mapOf(key to 7),
                fieldCheckerResults = mapOf(key to stored),
            )
            if (stored == null) {
                assertTrue(result.correctResolution(operation, selections))
                assertTrue(applications.hasExactlyCheckerApplications(result.registeredCheckerApplications(operation)))
            } else {
                assertFalse(result.correctResolution(operation, selections), "Unregistered field-check result: $stored")
                // This ledger also visits slots outside the checked selections (e.g. raw checker inputs).
                assertFailsWith<IllegalStateException> { result.registeredCheckerApplications(operation) }
            }
        }
    }

    private object Denial : CheckerResult.Error {
        override val error = IllegalStateException("denied")

        override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

        override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
    }
}
