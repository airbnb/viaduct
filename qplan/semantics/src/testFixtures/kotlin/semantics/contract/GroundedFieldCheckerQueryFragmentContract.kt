package semantics.contract

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import model.Arguments
import model.arg
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.operationSelectionsFrom
import model.registry.CheckerInput
import model.registry.FieldChecker
import model.registry.ResolverFragmentTemplates
import model.registry.VariableDefinition
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.shared.ResolverInvocationObservation
import semantics.shared.ResolverObserver
import semantics.shared.SharedOERContext
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult

/** Owner-local raw checker projections from the orchestration's shared Query OER. */
interface GroundedFieldCheckerQueryFragmentContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `shares one Query OER while preserving owner-local checker projections`() {
        val checkerInputs =
            Collections.synchronizedList(mutableListOf<Map<String, CheckerInput>>())
        val queryOERs = Collections.synchronizedList(mutableListOf<SharedOERContext>())
        val sharedCheckerCalls = AtomicInteger()
        val sharedResolverCalls = AtomicInteger()
        val world =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {})
                      shared(seed: Int!): Int! @resolver(result: 7)
                    }

                    type Item {
                      checked(seed: Int!): Int! @resolver(result: 1)
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    val query = schema.requireQueryTypeDef()
                    val checked = schema.requireObjectField("Item", "checked")
                    val seed = Arguments.Variable.of(checked, "seed")
                    fun queryInput(alias: String): ResolverFragmentTemplates =
                        ResolverFragmentTemplates(
                            objectFragmentTemplate = materializeSelectionForestOf(),
                            queryFragmentTemplate =
                                schema
                                    .fragmentFrom(
                                        "fragment Input on Query { $alias: shared(seed: ${'$'}seed) }",
                                        variableField = checked,
                                    ).materializeSelections,
                            variables =
                                mapOf<Arguments.Variable, VariableDefinition>(
                                    seed to
                                        VariableDefinition.FromArgument.of(
                                            checkNotNull(checked.arg("seed")),
                                        ),
                                ),
                        )
                    val shared = schema.requireObjectField("Query", "shared")
                    mapOf(
                        checked to
                            FieldChecker.of(
                                field = checked,
                                queryType = query,
                                fragmentTemplates =
                                    linkedMapOf(
                                        "first" to queryInput("firstValue"),
                                        "second" to queryInput("secondValue"),
                                        "empty" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate = materializeSelectionForestOf(),
                                                queryFragmentTemplate = materializeSelectionForestOf(),
                                            ),
                                    ),
                            ) { _, inputs, _ ->
                                checkerInputs += inputs
                                CheckerResult.Success
                            },
                        shared to
                            FieldChecker.of(shared, query) { _, _, _ ->
                                sharedCheckerCalls.incrementAndGet()
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions
        val observer =
            object : ResolverObserver {
                override fun onQueryOERPrepared(queryOER: SharedOERContext, queryOERDepth: Int?) {
                    if (queryOER.closedDemand.groundKeys().any { it.field.name == "shared" }) {
                        queryOERs += queryOER
                    }
                }

                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    if (observation.field.name == "shared") sharedResolverCalls.incrementAndGet()
                }
            }

        coroutineResolverSubject.resolve(
            SharedOperationContext.create(world, resolverObserver = observer),
            world.operationSelectionsFrom(
                "{ item { first: checked(seed: 1) second: checked(seed: 2) } }",
            ),
        )

        assertEquals(2, checkerInputs.size)
        assertEquals(1, queryOERs.size)
        checkerInputs.forEach { inputs ->
            assertEquals(7, inputs.getValue("first").queryValue.get("firstValue"))
            assertEquals(7, inputs.getValue("second").queryValue.get("secondValue"))
            assertEquals(emptySet(), inputs.getValue("empty").queryValue.getSelections())
        }
        assertNotSame(
            checkerInputs[0].getValue("first").queryValue,
            checkerInputs[1].getValue("first").queryValue,
        )
        assertEquals(2, sharedResolverCalls.get())
        assertEquals(0, sharedCheckerCalls.get())
    }
}
