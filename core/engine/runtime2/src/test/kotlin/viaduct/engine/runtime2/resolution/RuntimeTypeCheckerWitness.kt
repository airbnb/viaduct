package viaduct.engine.runtime2.resolution

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.arbitrary.ResolverTestCoordinates
import viaduct.engine.runtime2.contract.CheckerApplicationRecorder
import viaduct.engine.runtime2.contract.registeredCheckerApplications
import viaduct.engine.runtime2.correctresolution.CorrectnessCheckerObserver
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

/** Directed regression for named providers, excluded paths, and nested-list occurrences. */
internal fun validateRuntimeTypeCheckerCase(
    mode: GeneratedTypeCheckerMode,
    coordinates: ResolverTestCoordinates,
    dispatcher: CoroutineContext,
) {
    val random = Random(
        coordinates.seed xor (coordinates.schemaIndex.toLong() shl 32) xor
            (coordinates.registryIndex.toLong() shl 16) xor coordinates.queryIndex.toLong()
    )
    val token = random.nextInt(1, 1000) * 2
    val viewer = random.nextInt(1, 1000)
    val provided = random.nextInt(1, 1000)
    val enabled = coordinates.queryIndex % 2 == 0
    val providerCalls = AtomicInteger()
    val world = TestWorld.fromDSL(
        """
        extend type Query {
          items: [[Item!]!]! @resolver(result: [[{source: {token: $token}, protected: 1}], [{source: {token: ${token + 1}}, protected: 1}]])
          viewer: Int! @resolver(of: "gate", result: $viewer)
          gate: Int! @resolver(result: 1)
          echo(value: Int): Int @resolver(result: "value(${'$'}value)")
        }
        type Item {
          source: Source!
          protected: Int!
          derived: Int! @resolver(of: "protected", result: 5)
          echo(value: Int): Int @resolver(result: "value(${'$'}value)")
        }
        type Source { token: Int! }
        """.trimIndent(),
        fieldCheckers = { schema ->
            listOf(schema.loweredSchema.requireObjectField("Query", "gate"), schema.loweredSchema.requireObjectField("Item", "protected"))
                .associateWith { field -> FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ -> RuntimeTypeDenial } }
        },
        typeCheckers = { schema ->
            val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
            val source = schema.loweredSchema.requireType("Source") as ViaductSchema.Object
            val target = ResolverTarget.TypeCheckerTarget(item)

            fun pair(offset: Int): ResolverFragmentTemplates =
                ResolverFragmentTemplates(
                    schema.fragmentFrom(
                        "fragment Input on Item { source { token } localSource: source @include(if: ${'$'}enabled) { token } remote: echo(value: ${'$'}remote) provided: echo(value: ${'$'}provided) derived }",
                        variableTarget = target,
                    ).materializeSelections,
                    schema.fragmentFrom("fragment Input on Query { viewer local: echo(value: ${'$'}local) }", variableTarget = target).materializeSelections,
                    mapOf(
                        Arguments.Variable.of(target, "enabled") to VariableDefinition.FromProvider,
                        Arguments.Variable.of(target, "provided") to VariableDefinition.FromProvider,
                        Arguments.Variable.of(target, "local") to VariableDefinition.FromField.of(
                            ProviderFragment.OBJECT,
                            listOf(
                                ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField("Item", "source"), emptyMap()),
                                ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField("Source", "token"), emptyMap())
                            ),
                            listOf("localSource", "token"),
                        ),
                        Arguments.Variable.of(target, "remote") to VariableDefinition.FromField.of(
                            ProviderFragment.QUERY,
                            listOf(ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField("Query", "viewer"), emptyMap())),
                            listOf("viewer"),
                        ),
                    ),
                    variablesProvider = { args ->
                        assertTrue(args.fieldValues.isEmpty())
                        providerCalls.incrementAndGet()
                        mapOf("enabled" to enabled, "provided" to provided + offset)
                    },
                )
            mapOf(
                item to TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef(), mapOf("left" to pair(0), "right" to pair(1))) { inputs, _ ->
                    val localToken = (inputs.getValue("left").objectValue.get("source") as viaduct.engine.api.EngineObjectData.Sync).get("token") as Int
                    inputs.forEach { (name, input) ->
                        assertEquals(viewer, input.objectValue.get("remote"))
                        assertEquals(provided + if (name == "left") 0 else 1, input.objectValue.get("provided"))
                        assertEquals(5, input.objectValue.get("derived"))
                        assertEquals(if (enabled) localToken else null, input.queryValue.get("local"))
                    }
                    if (mode == GeneratedTypeCheckerMode.DENIAL || (mode == GeneratedTypeCheckerMode.MIXED && localToken % 2 == 1)) RuntimeTypeDenial else CheckerResult.Success
                },
                source to TypeCheckerResolver.of(source, schema.loweredSchema.requireQueryTypeDef()) { _, _ -> error("Raw-only Source must not be checked") },
            )
        },
    )
    // Both permutations retain the same directed distribution and independent judgments.
    listOf("{ items { __typename } }", "{ alias: items { __typename } }").forEach { query ->
        providerCalls.set(0)
        val applications = CheckerApplicationRecorder()
        val checkerObserver = CorrectnessCheckerObserver(applications)
        val resolverObserver = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = resolverObserver, checkerObserver = checkerObserver)
        val selections = world.schemas.operationSelectionsFrom(query)
        val result = operation.resolve(selections, dispatcher)
        assertEquals(4, providerCalls.get(), coordinates.summary())
        val typeCalls = applications.checkerApplications().filter { it.checkerKind == CheckerKind.TYPE }
        assertEquals(2, typeCalls.size)
        assertTrue(typeCalls.all { it.occurrencePath.count { component -> component is ListEngineResult.Index } == 2 })
        assertEquals(4, applications.checkerApplications().count { it.checkerKind == CheckerKind.FIELD })
        assertEquals(2, resolverObserver.allResolverInvocations().count { it.field.name == "viewer" })
        assertEquals(2, resolverObserver.allResolverInvocations().count { it.field.name == "derived" })
        assertEquals(2, checkerObserver.allQueryFragmentResults().values.flatten().toSet().size)
        typeCalls.forEach { call ->
            val checker = world.assumptions.resolverRegistry.typeChecker(call.checkedType)!!
            val fragments = checker.instantiateFragmentsAt(call.logicalQueryRoot, call.occurrencePath)
            (fragments.objectFragment.variableDefinitions + fragments.queryFragment.variableDefinitions).distinctBy { it.variable }.forEach { definition ->
                val binding = operation.variableBindings.getBinding(definition.variable.instanceId!!)
                val name = definition.variable.variableName
                when (name.substringAfter(':')) {
                    "enabled" -> assertEquals(VariableBinding.of(enabled), binding)
                    "provided" -> assertEquals(VariableBinding.of(provided + if (name.startsWith("left:")) 0 else 1), binding)
                    "remote" -> assertEquals(VariableBinding.of(viewer), binding)
                    "local" -> assertTrue(binding == VariableBinding.of(if (enabled) token else null) || binding == VariableBinding.of(if (enabled) token + 1 else null))
                }
            }
        }
        assertTrue(result.correctResolution(operation, selections.merge(result.type)), coordinates.summary())
        assertTrue(applications.hasExactlyCheckerApplications(result.registeredCheckerApplications(operation)), coordinates.summary())
    }
}

private object RuntimeTypeDenial : CheckerResult.Error {
    override val error = IllegalStateException("runtime type witness denial")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
