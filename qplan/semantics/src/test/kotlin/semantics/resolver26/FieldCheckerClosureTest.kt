package semantics.resolver26

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.ObjectEngineResult
import model.arg
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.operationSelectionsFrom
import model.registry.FieldCheckerResolver
import model.registry.ResolverTarget
import model.registry.ResolverFragmentTemplates
import model.registry.VariableDefinition
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult

class FieldCheckerClosureTest : Resolver26DispatcherResource {
    @Test
    fun `closure restores checked demand at raw value resolver boundaries on both roots`() =
        runBlocking {
            val world = TestWorld.fromDSL(
                """
            extend type Query {
              checked: Int! @resolver(result: 1)
              raw: Int! @resolver(of: "protected", result: 2)
              protected: Int! @resolver(result: 3)
            }
                """.trimIndent(),
                fieldCheckers = { schema ->
                    val checked = schema.requireObjectField("Query", "checked")
                    val raw = schema.requireObjectField("Query", "raw")
                    val protected = schema.requireObjectField("Query", "protected")
                    val fragment = schema.fragmentFrom("fragment Input on Query { raw }").materializeSelections
                    mapOf(
                        checked to FieldCheckerResolver.of(checked, schema.requireQueryTypeDef(), mapOf("input" to ResolverFragmentTemplates(fragment, fragment))) { _, _, _ -> CheckerResult.Success },
                        raw to FieldCheckerResolver.of(raw, schema.requireQueryTypeDef()) { _, _, _ -> error("raw checker must not run") },
                        protected to FieldCheckerResolver.of(protected, schema.requireQueryTypeDef()) { _, _, _ -> CheckerResult.Success },
                    )
                },
            )
            val operation = OperationContext.create(SharedOperationContext.create(world.assumptions), this)
            val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
            val task = OrchestrationTask.create(operation, OEROccurrence(root, emptyList(), root), world.assumptions.resolverRegistry.createRootQueryInput(), world.assumptions.operationSelectionsFrom("{ checked }"))
            assertEquals(
                setOf("checked", "protected"),
                task.closedConstructionDemand.objectRooted.constructionDemand.checked
                    .byKey()
                    .keys
                    .map { it.field.name }
                    .toSet()
            )
            assertEquals(
                setOf("raw"),
                task.closedConstructionDemand.objectRooted.constructionDemand.unchecked
                    .byKey()
                    .keys
                    .map { it.field.name }
                    .toSet()
            )
            assertEquals(
                setOf("protected"),
                task.closedConstructionDemand.queryRooted.constructionDemand.checked
                    .byKey()
                    .keys
                    .map { it.field.name }
                    .toSet()
            )
            assertEquals(
                setOf("raw"),
                task.closedConstructionDemand.queryRooted.constructionDemand.unchecked
                    .byKey()
                    .keys
                    .map { it.field.name }
                    .toSet()
            )
            operation.dispatcher.dispatchOrchestration(task)
        }

    @Test
    fun `statically excluded recursion does not trigger the symbolic expansion guard`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              checked(seed: Int!): Int!
                @resolver(of: "checked(seed: ${'$'}seed) @skip(if: true)", result: 1)
            }
            """.trimIndent(),
        )
        val operation = SharedOperationContext.create(world.assumptions)
        val result = operation.resolveWithTestDispatcher(world.assumptions.operationSelectionsFrom("{ checked(seed: 1) }"))
        val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "checked"), mapOf("seed" to 1))
        assertEquals(1, result.getCell(key).getValue().get())
    }

    @Test
    fun `unbounded symbolic checker expansion fails during preparation`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              checked(seed: Int!): Int! @resolver(result: 1)
              bridge(seed: Int!): Int! @resolver(of: "checked(seed: ${'$'}seed)", result: 2)
            }
            """.trimIndent(),
            fieldCheckers = { schema ->
                val checked = schema.requireObjectField("Query", "checked")
                val pair = ResolverFragmentTemplates(
                    schema.fragmentFrom("fragment Input on Query { bridge(seed: ${'$'}seed) }", variableTarget = ResolverTarget.FieldCheckerTarget(checked)).materializeSelections,
                    materializeSelectionForestOf(),
                    mapOf(Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(checked), "seed") to VariableDefinition.FromArgument.of(checkNotNull(checked.arg("seed")))),
                )
                mapOf(checked to FieldCheckerResolver.of(checked, schema.requireQueryTypeDef(), mapOf("input" to pair)) { _, _, _ -> CheckerResult.Success })
            },
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            SharedOperationContext.create(world.assumptions).resolveWithTestDispatcher(world.assumptions.operationSelectionsFrom("{ checked(seed: 1) }"))
        }
        assertTrue(failure.message.orEmpty().contains("Unbounded symbolic"))
    }
}
