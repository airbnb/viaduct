package viaduct.engine.runtime2.resolvers.resolver01

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom

class FieldCheckerIsolationTest {
    @Test
    fun `value-only resolver ignores checker definitions and never dispatches checker work`() {
        val checkerCalls = AtomicInteger()
        val worldFixture =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      value: Int! @resolver(result: 7)
                    }
                    """.trimIndent(),
                selectiveResolvers = false,
                fieldCheckers = { schema ->
                    val field = schema.loweredSchema.requireObjectField("Query", "value")
                    mapOf(
                        field to
                            FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                checkerCalls.incrementAndGet()
                                CheckerResult.Success
                            },
                    )
                },
            )
        val world = worldFixture.assumptions

        val result = SharedOperationContext.create(world).resolve(worldFixture.schemas.operationSelectionsFrom("{ value }"))
        val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "value"), emptyMap())

        assertEquals(7, result.getCell(key).value.get())
        assertEquals(0, checkerCalls.get())
        assertNull(result.getCell(key).fieldCheckerResult.get())
    }
}
