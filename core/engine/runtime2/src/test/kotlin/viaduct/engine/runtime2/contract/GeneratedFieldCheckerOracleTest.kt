package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.correctresolution.CorrectnessCheckerObserver
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class GeneratedFieldCheckerOracleTest {
    @Test
    fun `an unregistered field cannot publish a non-null checker result`() {
        val worldFixture =
            TestWorld.fromSDL(
                schemaSDL = "type Query { value: Int! }",
                fieldResolvers = { schema ->
                    val value = schema.loweredSchema.requireObjectField("Query", "value")
                    mapOf(
                        value to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    )
                },
            )
        val world = worldFixture.assumptions
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
        val selections = worldFixture.schemas.fragmentFrom("fragment Query on Query { value }")

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
