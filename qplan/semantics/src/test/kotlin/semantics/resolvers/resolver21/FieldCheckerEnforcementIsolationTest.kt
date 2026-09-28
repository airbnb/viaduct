package semantics.resolvers.resolver21

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import model.ObjectEngineResult
import model.operationSelectionsFrom
import model.registry.FieldCheckerResolver
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext

class FieldCheckerEnforcementIsolationTest {
    @Test
    fun `Resolver21 checker denial remains separate from the raw value slot`() {
        val denial = Resolver21Denial()
        val world =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      denied: Int! @resolver(result: 7)
                    }
                    """.trimIndent(),
                selectiveResolvers = false,
                fieldCheckers = { schema ->
                    val field = schema.requireObjectField("Query", "denied")
                    mapOf(
                        field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef()) { _, _, _ -> denial },
                    )
                },
            ).assumptions

        val result = SharedOperationContext.create(world).resolve(world.operationSelectionsFrom("{ denied }"))
        val cell =
            result.getCell(
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "denied"),
                    emptyMap(),
                ),
            )

        assertEquals(7, cell.getValue().get())
        assertSame(denial, cell.getFieldCheckerResult().get())
    }
}

private class Resolver21Denial : CheckerResult.Error {
    override val error: Exception = IllegalStateException("denied")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
