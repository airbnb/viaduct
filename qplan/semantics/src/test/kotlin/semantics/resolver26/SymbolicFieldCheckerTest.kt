package semantics.resolver26

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import model.Arguments
import model.ObjectEngineResult
import model.VariableBinding
import model.arg
import model.fragmentFrom
import model.merge
import model.operationSelectionsFrom
import model.registry.FieldCheckerResolver
import model.registry.ResolverTarget
import model.registry.ProviderFragment
import model.registry.ResolverFragmentTemplates
import model.registry.VariableDefinition
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.contract.CheckerApplicationRecorder
import semantics.contract.registeredFieldCheckerApplications
import semantics.correctresolution.CorrectnessCheckerObserver
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.correctResolution
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult

class SymbolicFieldCheckerTest : Resolver26DispatcherResource {
    @Test
    fun `checker field bindings preserve null intermediates terminals and nested errors on both roots`() {
        for (providerRoot in ProviderFragment.entries) {
            for (source in listOf("null", "{token: null}", "{token: \"ERROR\"}", "{token: [1, \"ERROR\"]}")) {
                val world = TestWorld.fromDSL(
                    """
                    extend type Query {
                      checked: Int! @resolver(result: 1)
                      source: Source @resolver(result: $source)
                      echo(value: [Int]): Int @resolver(result: 1)
                    }
                    type Source { token: [Int] }
                    """.trimIndent(),
                    fieldCheckers = { schema ->
                        val field = schema.requireObjectField("Query", "checked")
                        val input = schema.fragmentFrom(
                            "fragment Input on Query { source { token } echo(value: ${'$'}v) }",
                            variableTarget = ResolverTarget.FieldCheckerTarget(field),
                        ).materializeSelections
                        val empty = model.materializeSelectionForestOf()
                        val pair = ResolverFragmentTemplates(
                            if (providerRoot == ProviderFragment.OBJECT) input else empty,
                            if (providerRoot == ProviderFragment.QUERY) input else empty,
                            mapOf(Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(field), "v") to VariableDefinition.FromField.of(
                                providerRoot,
                                listOf(
                                    ObjectEngineResult.Key.of(schema.requireObjectField("Query", "source"), emptyMap()),
                                    ObjectEngineResult.Key.of(schema.requireObjectField("Source", "token"), emptyMap()),
                                ),
                                listOf("source", "token"),
                            )),
                        )
                        mapOf(field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef(), mapOf("input" to pair)) { _, _, _ -> CheckerResult.Success })
                    },
                )
                val resolution = resolveChecked(world, "{ checked }")
                val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "checked"), emptyMap())
                val fragments = checkNotNull(world.assumptions.resolverRegistry.fieldChecker(key.field))
                    .instantiateFragmentsAt(resolution.result, listOf(key))
                val variable = (fragments.objectFragment.variableDefinitions + fragments.queryFragment.variableDefinitions)
                    .distinctBy { it.variable }.single().variable
                val expected = if ("ERROR" in source) VariableBinding.Error else VariableBinding.of(null)
                assertEquals(expected, resolution.operation.variableBindings.getBinding(requireNotNull(variable.instanceId)), "$providerRoot $source")
            }
        }
    }

    @Test
    fun `named pairs bind arguments both roots and independent providers at list occurrences`() {
        val providerCalls = AtomicInteger()
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              items: [Item!]! @resolver(result: [{token: 3}, {token: 5}])
              viewer: Int! @resolver(result: 11)
              echo(value: Int): Int @resolver(result: "value(${'$'}value)")
            }
            type Item {
              token: Int!
              result(seed: Int!): Int! @resolver(result: "sum(${'$'}seed)")
              echo(value: Int): Int @resolver(result: "value(${'$'}value)")
            }
            """.trimIndent(),
            fieldCheckers = { schema ->
                val field = schema.requireObjectField("Item", "result")
                val objectSource = "fragment Input on Item { token remote: echo(value: ${'$'}remote) argument: echo(value: ${'$'}arg) }"
                val querySource = "fragment Input on Query { viewer local: echo(value: ${'$'}local) provided: echo(value: ${'$'}provided) }"

                fun pair(value: Int) =
                    ResolverFragmentTemplates(
                        objectFragmentTemplate = schema.fragmentFrom(objectSource, variableTarget = ResolverTarget.FieldCheckerTarget(field)).materializeSelections,
                        queryFragmentTemplate = schema.fragmentFrom(querySource, variableTarget = ResolverTarget.FieldCheckerTarget(field)).materializeSelections,
                        variables = mapOf(
                            Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(field), "remote") to VariableDefinition.FromField.of(ProviderFragment.QUERY, listOf(ObjectEngineResult.Key.of(schema.requireObjectField("Query", "viewer"), emptyMap())), listOf("viewer")),
                            Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(field), "local") to VariableDefinition.FromField.of(ProviderFragment.OBJECT, listOf(ObjectEngineResult.Key.of(schema.requireObjectField("Item", "token"), emptyMap())), listOf("token")),
                            Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(field), "arg") to VariableDefinition.FromArgument.of(checkNotNull(field.arg("seed"))),
                            Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(field), "provided") to VariableDefinition.FromProvider,
                        ),
                        variablesProvider = {
                            providerCalls.incrementAndGet()
                            mapOf("provided" to value)
                        },
                    )
                mapOf(
                    field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef(), mapOf("left" to pair(17), "right" to pair(19))) { arguments, inputs, _ ->
                        inputs.forEach { (name, input) ->
                            assertEquals(11, input.objectValue.get("remote"))
                            assertEquals(arguments.fieldValues["seed"], input.objectValue.get("argument"))
                            assertEquals(input.objectValue.get("token"), input.queryValue.get("local"))
                            assertEquals(if (name == "left") 17 else 19, input.queryValue.get("provided"))
                        }
                        CheckerResult.Success
                    }
                )
            },
        )
        val resolution = resolveChecked(world, "{ items { a: result(seed: 7) b: result(seed: 9) } }")
        assertEquals(4, resolution.recorder.checkerApplications().size)
        assertEquals(8, providerCalls.get())
        assertEquals(
            2,
            resolution.checkers
                .allQueryFragmentResults()
                .values
                .flatten()
                .toSet()
                .size
        )
        val invocations = resolution.resolvers.allResolverInvocations()
        assertEquals(2, invocations.count { it.field.name == "viewer" })
        assertEquals(4, invocations.count { it.field.name == "result" })
    }

    @Test
    fun `checker provider exclusion is alias local even when another pair demands the same physical cell`() {
        listOf(false, true).forEach { enabled ->
            val world = TestWorld.fromDSL(
                """
                extend type Query {
                  item: Item! @resolver(result: {token: 8, protected: 1})
                  echo(value: Int): Int @resolver(result: "value(${'$'}value)")
                }
                type Item { token: Int! protected: Int! }
                """.trimIndent(),
                fieldCheckers = { schema ->
                    val field = schema.requireObjectField("Item", "protected")
                    val objectSource = "fragment Input on Item { excluded: token @include(if: ${'$'}enabled) included: token }"
                    val querySource = "fragment Input on Query { excluded: echo(value: ${'$'}excluded) included: echo(value: ${'$'}included) }"
                    val pair = ResolverFragmentTemplates(
                        schema.fragmentFrom(objectSource, variableTarget = ResolverTarget.FieldCheckerTarget(field)).materializeSelections,
                        schema.fragmentFrom(querySource, variableTarget = ResolverTarget.FieldCheckerTarget(field)).materializeSelections,
                        mapOf(
                            Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(field), "enabled") to VariableDefinition.FromProvider,
                            Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(field), "excluded") to VariableDefinition.FromField.of(ProviderFragment.OBJECT, listOf(ObjectEngineResult.Key.of(schema.requireObjectField("Item", "token"), emptyMap())), listOf("excluded")),
                            Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(field), "included") to VariableDefinition.FromField.of(ProviderFragment.OBJECT, listOf(ObjectEngineResult.Key.of(schema.requireObjectField("Item", "token"), emptyMap())), listOf("included")),
                        ),
                        variablesProvider = { mapOf("enabled" to enabled) },
                    )
                    val physical = ResolverFragmentTemplates(
                        schema.fragmentFrom("fragment Physical on Item { token }").materializeSelections,
                        model.materializeSelectionForestOf(),
                    )
                    mapOf(
                        field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef(), mapOf("local" to pair, "physical" to physical)) { _, inputs, _ ->
                            val input = inputs.getValue("local")
                            assertEquals(enabled, input.objectValue.isPresent("excluded"))
                            assertEquals(if (enabled) 8 else null, input.queryValue.get("excluded"))
                            assertEquals(8, input.queryValue.get("included"))
                            assertEquals(8, inputs.getValue("physical").objectValue.get("token"))
                            CheckerResult.Success
                        }
                    )
                },
            )
            val resolution = resolveChecked(world, "{ item { protected } }")
            assertEquals(1, resolution.recorder.checkerApplications().size)
        }
    }

    @Test
    fun `raw overlap does not activate a checker whose checked occurrence is excluded`() {
        val forbidden = AtomicInteger()
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              checked: Int! @resolver(of: "dependency @include(if: ${'$'}enabled)", providerVars: {enabled: false}, result: 1)
              raw: Int! @resolver(result: 2)
              dependency: Int! @resolver(result: 7)
            }
            """.trimIndent(),
            fieldCheckers = { schema ->
                val raw = schema.requireObjectField("Query", "raw")
                val dependency = schema.requireObjectField("Query", "dependency")
                mapOf(
                    raw to FieldCheckerResolver.of(
                        raw,
                        schema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment Input on Query { dependency }").materializeSelections,
                                model.materializeSelectionForestOf(),
                            )
                        )
                    ) { _, inputs, _ ->
                        assertEquals(7, inputs.getValue("input").objectValue.get("dependency"))
                        CheckerResult.Success
                    },
                    dependency to FieldCheckerResolver.of(
                        dependency,
                        schema.requireQueryTypeDef(),
                        mapOf("excluded" to ResolverFragmentTemplates(
                            schema.fragmentFrom("fragment Input on Query { checked @include(if: ${'$'}enabled) }", variableTarget = ResolverTarget.FieldCheckerTarget(dependency)).materializeSelections,
                            model.materializeSelectionForestOf(),
                            mapOf(Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(dependency), "enabled") to VariableDefinition.FromProvider),
                            variablesProvider = { error("Excluded checker provider must not run") },
                        )),
                    ) { _, _, _ ->
                        forbidden.incrementAndGet()
                        CheckerResult.Success
                    },
                )
            },
        )
        val resolution = resolveChecked(world, "{ checked raw }")
        assertEquals(0, forbidden.get())
        assertEquals(listOf("raw"), resolution.recorder.checkerApplications().map { it.checkedCoordinate.name })
        val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "dependency"), emptyMap())
        assertEquals(
            7,
            resolution.result
                .getCell(key)
                .value
                .get()
        )
        assertNull(
            resolution.result
                .getCell(key)
                .fieldCheckerResult
                .get()
        )
        val fragments = checkNotNull(world.assumptions.resolverRegistry.fieldChecker(key.field))
            .instantiateFragmentsAt(resolution.result, listOf(key))
        val variable = fragments.objectFragment.variableDefinitions.single().variable
        assertEquals(VariableBinding.of(null), resolution.operation.variableBindings.getBinding(requireNotNull(variable.instanceId)))
    }

    @Test
    fun `equal grounded checker arguments keep distinct symbolic occurrences`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              first: Int! @resolver(of: "checked(value: ${'$'}v)", providerVars: {v: 7}, result: 1)
              second: Int! @resolver(of: "checked(value: ${'$'}v)", providerVars: {v: 7}, result: 2)
              checked(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
            }
            """.trimIndent(),
            fieldCheckers = { schema ->
                val field = schema.requireObjectField("Query", "checked")
                mapOf(field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef()) { _, _, _ -> CheckerResult.Success })
            },
        )
        val resolution = resolveChecked(world, "{ first second }")
        val calls = resolution.recorder.checkerApplications()
        assertEquals(2, calls.size)
        assertEquals(calls[0].arguments, calls[1].arguments)
        assertFalse(calls[0].occurrencePath == calls[1].occurrencePath)
    }

    private fun resolveChecked(
        world: TestWorld,
        query: String
    ): Resolution {
        val recorder = CheckerApplicationRecorder()
        val checkers = CorrectnessCheckerObserver(recorder)
        val resolvers = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = resolvers, checkerObserver = checkers)
        val selections = world.assumptions.operationSelectionsFrom(query)
        val result = operation.resolveWithTestDispatcher(selections)
        assertTrue(result.correctResolution(operation, selections.merge(result.type)), "checker-aware correctness")
        assertTrue(recorder.hasExactlyCheckerApplications(result.registeredFieldCheckerApplications(operation)))
        return Resolution(result, recorder, checkers, resolvers, operation)
    }

    private class Resolution(
        val result: ObjectEngineResult,
        val recorder: CheckerApplicationRecorder,
        val checkers: CorrectnessCheckerObserver,
        val resolvers: CorrectnessResolverObserver,
        val operation: SharedOperationContext<*>,
    )
}
