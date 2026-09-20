package semantics.resolvers.resolver21

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.operationSelectionsFrom
import model.registry.FieldChecker
import model.registry.ResolverFragmentTemplates
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult

class FieldCheckerRequiredSelectionsTest {
    @Test
    fun `Resolver21 rejects checker required selections`() {
        for (queryRooted in listOf(false, true)) {
            val world =
                TestWorld.fromDSL(
                    schemaSDL =
                        """
                        extend type Query {
                          checked: Int! @resolver(result: 1)
                        }
                        """.trimIndent(),
                    selectiveResolvers = false,
                    fieldCheckers = { schema ->
                        val query = schema.requireQueryTypeDef()
                        val checked = schema.requireObjectField("Query", "checked")
                        val fragment =
                            schema.fragmentFrom("fragment Input on Query { checked }").materializeSelections
                        val fragmentTemplates =
                            ResolverFragmentTemplates(
                                objectFragmentTemplate =
                                    if (queryRooted) materializeSelectionForestOf() else fragment,
                                queryFragmentTemplate =
                                    if (queryRooted) fragment else materializeSelectionForestOf(),
                            )
                        mapOf(
                            checked to
                                FieldChecker.of(
                                    checked,
                                    query,
                                    fragmentTemplates = mapOf("input" to fragmentTemplates),
                                ) { _, _, _ -> CheckerResult.Success },
                        )
                    },
                ).assumptions

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    SharedOperationContext.create(world).resolve(
                        world.operationSelectionsFrom("{ checked }"),
                    )
                }
            assertTrue(failure.message.orEmpty().contains("cannot declare"))
        }
    }
}
