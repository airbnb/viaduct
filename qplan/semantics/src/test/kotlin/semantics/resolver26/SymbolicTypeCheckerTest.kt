package semantics.resolver26

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.Arguments
import model.ObjectEngineResult
import model.RootFieldReferenceData
import model.VariableBinding
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.parsing.operationSelectionsFrom
import model.registry.ProviderFragment
import model.registry.ResolverFragmentTemplates
import model.registry.ResolverTarget
import model.registry.TypeCheckerResolver
import model.registry.VariableDefinition
import model.registry.fieldResolverOf
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import semantics.contract.CheckerApplicationRecorder
import semantics.contract.demandedTypeCheckerApplications
import semantics.contract.registeredCheckerApplications
import semantics.correctresolution.CorrectnessCheckerObserver
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.correctResolution
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

class SymbolicTypeCheckerTest : Resolver26DispatcherResource {
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
                    typeCheckers = { schema ->
                        val field = schema.loweredSchema.requireQueryTypeDef()
                        val input = schema.fragmentFrom(
                            "fragment Input on Query { source { token } echo(value: ${'$'}v) }",
                            variableTarget = ResolverTarget.TypeCheckerTarget(field),
                        ).materializeSelections
                        val empty = model.materializeSelectionForestOf()
                        val pair = ResolverFragmentTemplates(
                            if (providerRoot == ProviderFragment.OBJECT) input else empty,
                            if (providerRoot == ProviderFragment.QUERY) input else empty,
                            mapOf(
                                Arguments.Variable.of(ResolverTarget.TypeCheckerTarget(field), "v") to VariableDefinition.FromField.of(
                                    providerRoot,
                                    listOf(
                                        ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField("Query", "source"), emptyMap()),
                                        ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField("Source", "token"), emptyMap()),
                                    ),
                                    listOf("source", "token"),
                                )
                            ),
                        )
                        mapOf(field to TypeCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef(), mapOf("input" to pair)) { _, _ -> CheckerResult.Success })
                    },
                )
                val resolution = resolveChecked(world, "{ checked }")
                val fragments = checkNotNull(world.assumptions.resolverRegistry.typeChecker(resolution.result.type))
                    .instantiateFragmentsAt(resolution.result, emptyList())
                val variable = (fragments.objectFragment.variableDefinitions + fragments.queryFragment.variableDefinitions)
                    .distinctBy { it.variable }.single().variable
                val expected = if ("ERROR" in source) VariableBinding.Error else VariableBinding.of(null)
                assertEquals(expected, resolution.operation.variableBindings.getBinding(requireNotNull(variable.instanceId)), "$providerRoot $source")
            }
        }
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
                typeCheckers = { schema ->
                    val field = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    val objectSource = "fragment Input on Item { excluded: token @include(if: ${'$'}enabled) included: token }"
                    val querySource = "fragment Input on Query { excluded: echo(value: ${'$'}excluded) included: echo(value: ${'$'}included) }"
                    val pair = ResolverFragmentTemplates(
                        schema.fragmentFrom(objectSource, variableTarget = ResolverTarget.TypeCheckerTarget(field)).materializeSelections,
                        schema.fragmentFrom(querySource, variableTarget = ResolverTarget.TypeCheckerTarget(field)).materializeSelections,
                        mapOf(
                            Arguments.Variable.of(ResolverTarget.TypeCheckerTarget(field), "enabled") to VariableDefinition.FromProvider,
                            Arguments.Variable.of(
                                ResolverTarget.TypeCheckerTarget(field),
                                "excluded"
                            ) to VariableDefinition.FromField.of(
                                ProviderFragment.OBJECT,
                                listOf(ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField("Item", "token"), emptyMap())),
                                listOf("excluded")
                            ),
                            Arguments.Variable.of(
                                ResolverTarget.TypeCheckerTarget(field),
                                "included"
                            ) to VariableDefinition.FromField.of(
                                ProviderFragment.OBJECT,
                                listOf(ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField("Item", "token"), emptyMap())),
                                listOf("included")
                            ),
                        ),
                        variablesProvider = { mapOf("enabled" to enabled) },
                    )
                    val physical = ResolverFragmentTemplates(
                        schema.fragmentFrom("fragment Physical on Item { token }").materializeSelections,
                        model.materializeSelectionForestOf(),
                    )
                    mapOf(
                        field to TypeCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef(), mapOf("local" to pair, "physical" to physical)) { inputs, _ ->
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
    fun `named pairs bind both roots and independent providers at list occurrences`() {
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
            typeCheckers = { schema ->
                val field = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                val objectSource = "fragment Input on Item { token remote: echo(value: ${'$'}remote) }"
                val querySource = "fragment Input on Query { viewer local: echo(value: ${'$'}local) provided: echo(value: ${'$'}provided) }"

                fun pair(value: Int) =
                    ResolverFragmentTemplates(
                        objectFragmentTemplate = schema.fragmentFrom(objectSource, variableTarget = ResolverTarget.TypeCheckerTarget(field)).materializeSelections,
                        queryFragmentTemplate = schema.fragmentFrom(querySource, variableTarget = ResolverTarget.TypeCheckerTarget(field)).materializeSelections,
                        variables = mapOf(
                            Arguments.Variable.of(
                                ResolverTarget.TypeCheckerTarget(field),
                                "remote"
                            ) to VariableDefinition.FromField.of(
                                ProviderFragment.QUERY,
                                listOf(ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField("Query", "viewer"), emptyMap())),
                                listOf("viewer")
                            ),
                            Arguments.Variable.of(
                                ResolverTarget.TypeCheckerTarget(field),
                                "local"
                            ) to VariableDefinition.FromField.of(
                                ProviderFragment.OBJECT,
                                listOf(ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField("Item", "token"), emptyMap())),
                                listOf("token")
                            ),
                            Arguments.Variable.of(ResolverTarget.TypeCheckerTarget(field), "provided") to VariableDefinition.FromProvider,
                        ),
                        variablesProvider = { arguments ->
                            assertTrue(arguments.fieldValues.isEmpty())
                            providerCalls.incrementAndGet()
                            mapOf("provided" to value)
                        },
                    )
                mapOf(
                    field to TypeCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef(), mapOf("left" to pair(17), "right" to pair(19))) { inputs, _ ->
                        inputs.forEach { (name, input) ->
                            assertEquals(11, input.objectValue.get("remote"))
                            assertEquals(input.objectValue.get("token"), input.queryValue.get("local"))
                            assertEquals(if (name == "left") 17 else 19, input.queryValue.get("provided"))
                        }
                        CheckerResult.Success
                    }
                )
            },
        )
        val resolution = resolveChecked(world, "{ items { a: result(seed: 7) b: result(seed: 9) } }") {
            assertEquals(4, providerCalls.get())
        }
        assertEquals(2, resolution.recorder.checkerApplications().size)
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
    fun `checked OER provenance survives raw overlap and runtime exclusion`() {
        listOf(false, true).forEach { enabled ->
            val providers = AtomicInteger()
            val world = TestWorld.fromDSL(
                """
                extend type Query {
                  checked: Int! @resolver(of: "item @include(if: ${'$'}enabled) { id }", providerVars: {enabled: $enabled}, result: 1)
                  raw: Int! @resolver(result: 2)
                  item: Item! @resolver(result: {id: 7})
                  echo(value: Int): Int @resolver(result: "value(${'$'}value)")
                }
                type Item { id: Int! }
                """.trimIndent(),
                fieldCheckers = { schema ->
                    val raw = schema.loweredSchema.requireObjectField("Query", "raw")
                    mapOf(
                        raw to model.registry.FieldCheckerResolver.of(
                            raw,
                            schema.loweredSchema.requireQueryTypeDef(),
                            mapOf(
                                "input" to ResolverFragmentTemplates(
                                    schema.fragmentFrom("fragment Input on Query { item { id } }").materializeSelections,
                                    model.materializeSelectionForestOf(),
                                ),
                            )
                        ) { _, inputs, _ ->
                            assertEquals(7, (inputs.getValue("input").objectValue.get("item") as viaduct.engine.api.EngineObjectData.Sync).get("id"))
                            CheckerResult.Success
                        }
                    )
                },
                typeCheckers = { schema ->
                    val type = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    val target = ResolverTarget.TypeCheckerTarget(type)
                    mapOf(
                        type to TypeCheckerResolver.of(
                            type,
                            schema.loweredSchema.requireQueryTypeDef(),
                            mapOf(
                                "input" to ResolverFragmentTemplates(
                                    model.materializeSelectionForestOf(),
                                    schema.fragmentFrom("fragment Input on Query { echo(value: ${'$'}v) }", variableTarget = target).materializeSelections,
                                    mapOf(Arguments.Variable.of(target, "v") to VariableDefinition.FromProvider),
                                    variablesProvider = {
                                        providers.incrementAndGet()
                                        mapOf("v" to 7)
                                    },
                                ),
                            )
                        ) { inputs, _ ->
                            assertEquals(7, inputs.getValue("input").queryValue.get("echo"))
                            CheckerResult.Success
                        }
                    )
                },
            )
            val resolution = resolveChecked(world, "{ checked raw }") {
                assertEquals(if (enabled) 1 else 0, providers.get())
            }
            val itemKey = resolution.result.keys.single { it.field.name == "item" }
            val item = resolution.result.getCell(itemKey).value.get() as ObjectEngineResult
            assertEquals(if (enabled) CheckerResult.Success else null, item.typeCheckerResult.get())
            assertEquals(if (enabled) 2 else 1, resolution.recorder.checkerApplications().size)
            val fragments = world.assumptions.resolverRegistry.typeChecker(item.type)!!
                .instantiateFragmentsAt(resolution.result, listOf(itemKey))
            val variable = fragments.queryFragment.variableDefinitions.single().variable
            assertEquals(VariableBinding.of(if (enabled) 7 else null), resolution.operation.variableBindings.getBinding(variable.instanceId!!))
        }
    }

    @Test
    fun `equal grounded producing keys retain separate type occurrences`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              first: Int! @resolver(of: "item(value: ${'$'}v) { id }", providerVars: {v: 7}, result: 1)
              second: Int! @resolver(of: "item(value: ${'$'}v) { id }", providerVars: {v: 7}, result: 2)
              item(value: Int!): Item! @resolver(result: {id: 7})
            }
            type Item { id: Int! }
            """.trimIndent(),
            typeCheckers = { schema ->
                val type = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                mapOf(type to TypeCheckerResolver.of(type, schema.loweredSchema.requireQueryTypeDef()) { _, _ -> CheckerResult.Success })
            },
        )
        val resolution = resolveChecked(world, "{ first second }")
        val calls = resolution.recorder.checkerApplications()
        assertEquals(2, calls.size)
        assertFalse(calls[0].occurrencePath == calls[1].occurrencePath)
        assertTrue(calls.all { it.arguments == null })
    }

    @Test
    fun `type checker parent inputs are lifted before the ancestor freezes`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query { root: Root! @resolver(result: {}) }
            type Root {
              item: Item! @resolver(result: {id: 7})
              token: Int! @resolver(result: 11)
            }
            type Item { id: Int! parent: Root! @parent }
            """.trimIndent(),
            typeCheckers = { schema ->
                val type = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                mapOf(
                    type to TypeCheckerResolver.of(
                        type,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment Input on Item { parent { token } }").materializeSelections,
                                model.materializeSelectionForestOf(),
                            ),
                        )
                    ) { inputs, _ ->
                        assertEquals(11, (inputs.getValue("input").objectValue.get("parent") as viaduct.engine.api.EngineObjectData.Sync).get("token"))
                        CheckerResult.Success
                    }
                )
            },
        )
        val resolution = resolveChecked(world, "{ root { item { id } } }")
        assertEquals(1, resolution.recorder.checkerApplications().size)
        assertEquals(1, resolution.resolvers.allResolverInvocations().count { it.field.name == "token" })
    }

    @Test
    fun `checked parent demand checks a raw ancestor and expands its type inputs`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              raw: Int! @resolver(result: 1)
              wrapper: Wrapper! @resolver(result: {token: 7, secret: 11, child: {}})
            }
            type Wrapper { token: Int! secret: Int! child: Child! }
            type Child {
              parent: Wrapper! @parent
              computed: Int! @resolver(of: "parent { token }", result: 3)
            }
            """.trimIndent(),
            fieldCheckers = { schema ->
                val raw = schema.loweredSchema.requireObjectField("Query", "raw")
                mapOf(
                    raw to model.registry.FieldCheckerResolver.of(
                        raw,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment Input on Query { wrapper { child { computed } } }").materializeSelections,
                                model.materializeSelectionForestOf(),
                            ),
                        )
                    ) { _, _, _ -> CheckerResult.Success }
                )
            },
            typeCheckers = { schema ->
                val type = schema.loweredSchema.requireType("Wrapper") as ViaductSchema.Object
                mapOf(
                    type to TypeCheckerResolver.of(
                        type,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment Input on Wrapper { secret }").materializeSelections,
                                model.materializeSelectionForestOf(),
                            ),
                        )
                    ) { inputs, _ ->
                        assertEquals(11, inputs.getValue("input").objectValue.get("secret"))
                        CheckerResult.Success
                    }
                )
            },
        )
        val resolution = resolveChecked(world, "{ raw }")
        assertEquals(1, resolution.recorder.checkerApplications().count { it.checkerKind == semantics.shared.CheckerKind.TYPE })
    }

    @Test
    fun `independent replay rejects wrong target binding values even when resolver output agrees`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              value: Int! @resolver(result: 1)
              source: Int! @resolver(result: 7)
              echo(value: Int): Int @resolver(result: 1)
            }
            """.trimIndent(),
            typeCheckers = { schema ->
                val type = schema.loweredSchema.requireQueryTypeDef()
                val target = ResolverTarget.TypeCheckerTarget(type)
                mapOf(
                    type to TypeCheckerResolver.of(
                        type,
                        type,
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment Input on Query { source echo(value: ${'$'}v) }", variableTarget = target).materializeSelections,
                                model.materializeSelectionForestOf(),
                                mapOf(
                                    Arguments.Variable.of(target, "v") to VariableDefinition.FromField.of(
                                        ProviderFragment.OBJECT,
                                        listOf(ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField("Query", "source"), emptyMap())),
                                        listOf("source"),
                                    )
                                ),
                            ),
                        )
                    ) { _, _ -> CheckerResult.Success }
                )
            },
        )
        val resolution = resolveChecked(world, "{ value }")
        val definition = world.assumptions.resolverRegistry.typeChecker(resolution.result.type)!!
            .instantiateFragmentsAt(resolution.result, emptyList()).objectFragment.variableDefinitions.single()
        val forgedBindings = semantics.shared.VariableBindingsState().apply {
            bindVariable(definition.variable.instanceId!!, VariableBinding.of(99))
        }
        val forged = SharedOperationContext.create(
            world.assumptions,
            variableBindings = forgedBindings,
            resolverObserver = resolution.resolvers,
            checkerObserver = resolution.checkers
        )
        assertFalse(resolution.result.correctResolution(forged, world.schemas.operationSelectionsFrom("{ value }").merge(resolution.result.type)))
    }

    @Test
    fun `reference target Query roots prepare their own registered type result`() {
        val world = TestWorld.fromSDL(
            """
            type Query { reference: Int! target: Int! dependency: Int! policy: Int! }
            """.trimIndent(),
            fieldResolvers = { schema ->
                val empty = schema.loweredSchema.emptyFragmentOf("Query")
                val target = schema.loweredSchema.requireObjectField("Query", "target")
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "reference") to fieldResolverOf(empty) { _, _ ->
                        RootFieldReferenceData.of(listOf(target), emptyMap())
                    },
                    target to fieldResolverOf(
                        objectFragment = empty,
                        queryFragment = schema.fragmentFrom("fragment Input on Query { dependency }")
                    ) { _, query, _ -> query.get("dependency") },
                    schema.loweredSchema.requireObjectField("Query", "dependency") to fieldResolverOf(empty) { _, _ -> 7 },
                    schema.loweredSchema.requireObjectField("Query", "policy") to fieldResolverOf(empty) { _, _ -> 11 },
                )
            },
            typeCheckers = { schema ->
                val type = schema.loweredSchema.requireQueryTypeDef()
                mapOf(
                    type to TypeCheckerResolver.of(
                        type,
                        type,
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment Input on Query { policy }").materializeSelections,
                                model.materializeSelectionForestOf(),
                            ),
                        )
                    ) { inputs, _ ->
                        assertEquals(11, inputs.getValue("input").objectValue.get("policy"))
                        CheckerResult.Success
                    }
                )
            },
        )
        val resolution = resolveChecked(world, "{ reference }")
        val key = resolution.result.keys.single { it.field.name == "reference" }
        assertEquals(7, resolution.result.getCell(key).value.get())
        val calls = resolution.recorder.checkerApplications()
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.occurrencePath.isEmpty() && it.checkedType.name == "Query" })
        assertEquals(2, calls.map { it.logicalQueryRoot }.toSet().size)
    }

    private fun resolveChecked(
        world: TestWorld,
        query: String,
        beforeReplay: () -> Unit = {},
    ): Resolution {
        val recorder = CheckerApplicationRecorder()
        val checkers = CorrectnessCheckerObserver(recorder)
        val resolvers = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = resolvers, checkerObserver = checkers)
        val selections = world.schemas.operationSelectionsFrom(query)
        val result = operation.resolveWithTestDispatcher(selections)
        beforeReplay()
        assertTrue(result.correctResolution(operation, selections.merge(result.type)), "checker-aware correctness")
        assertTrue(recorder.hasExactlyCheckerApplications(result.registeredCheckerApplications(operation)))
        assertEquals(
            result.demandedTypeCheckerApplications(operation, selections).associateWith { 1 },
            recorder.checkerApplications().filter { it.checkerKind == semantics.shared.CheckerKind.TYPE }.groupingBy { it }.eachCount(),
        )
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
