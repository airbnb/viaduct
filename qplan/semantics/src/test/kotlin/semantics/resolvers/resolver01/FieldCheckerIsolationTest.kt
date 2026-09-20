package semantics.resolvers.resolver01

import java.util.concurrent.atomic.AtomicInteger
import model.ObjectEngineResult
import model.operationSelectionsFrom
import model.registry.FieldChecker
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FieldCheckerIsolationTest {
    @Test
    fun `value-only resolver ignores checker definitions and never dispatches checker work`() {
        val checkerCalls = AtomicInteger()
        val world =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      value: Int! @resolver(result: 7)
                    }
                    """.trimIndent(),
                selectiveResolvers = false,
                fieldCheckers = { schema ->
                    val field = schema.requireObjectField("Query", "value")
                    mapOf(
                        field to
                            FieldChecker.of(field, schema.requireQueryTypeDef()) { _, _, _ ->
                                checkerCalls.incrementAndGet()
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions

        val result = SharedOperationContext.create(world).resolve(world.operationSelectionsFrom("{ value }"))
        val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "value"), emptyMap())

        assertEquals(7, result.getCell(key).getValue().get())
        assertEquals(0, checkerCalls.get())
        assertFalse(result.getCell(key).isFieldCheckerResultSet())
    }
}
