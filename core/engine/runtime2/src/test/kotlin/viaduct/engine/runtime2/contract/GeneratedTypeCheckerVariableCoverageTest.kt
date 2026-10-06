package viaduct.engine.runtime2.contract

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.ResolverTarget
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.ResolutionDispatcherResource
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

class GeneratedTypeCheckerVariableCoverageTest : ResolverContract, ResolutionDispatcherResource {
    @Test
    fun `counts executed type owned path and provider variables used on both roots`() {
        val coverage = observation().typeCheckerCoverage()
        assertTrue(coverage.containsAll(variableSignatures))
    }

    @Test
    fun `registered variables without a checker invocation are not activation`() {
        val observation = observation().copy(checkerApplications = emptyList())
        assertFalse(observation.typeCheckerCoverage().any { it in variableSignatures })
    }

    @Test
    fun `a checker invocation without its completed bindings is not variable activation`() {
        val observation = observation()
        val withoutBindings = observation.copy(
            operation = SharedOperationContext.create(
                observation.world,
                resolverObserver = observation.operation.resolverObserver,
                checkerObserver = observation.operation.checkerObserver,
            ),
        )
        val coverage = withoutBindings.typeCheckerCoverage()
        assertTrue(GeneratedTypeCheckerSignature.TYPE_CHECKER in coverage)
        assertFalse(coverage.any { it in variableSignatures })
    }

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)

    private fun observation(): GeneratedResolutionObservation {
        val testWorld = TestWorld.fromDSL(
            """
            extend type Query {
              item: Item! @resolver(result: {source: {token: 1}})
              source: Source! @resolver(result: {token: 2})
              echo(value: Int!): Int! @resolver(result: "value(${'$'}value)")
            }
            type Item {
              source: Source!
              echo(value: Int!): Int! @resolver(result: "value(${'$'}value)")
            }
            type Source { token: Int! }
            """.trimIndent(),
            typeCheckers = { schema ->
                val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                val target = ResolverTarget.TypeCheckerTarget(item)
                val variables = ProviderFragment.entries.associate { source ->
                    val owner = if (source == ProviderFragment.OBJECT) "Item" else "Query"
                    val name = if (source == ProviderFragment.OBJECT) "local" else "remote"
                    Arguments.Variable.of(target, name) to VariableDefinition.FromField.of(
                        source,
                        listOf(
                            ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField(owner, "source"), emptyMap()),
                            ObjectEngineResult.Key.of(schema.loweredSchema.requireObjectField("Source", "token"), emptyMap()),
                        ),
                        listOf("source", "token"),
                    )
                }
                val allVariables = variables + (Arguments.Variable.of(target, "provided") to VariableDefinition.FromProvider)
                val pair = ResolverFragmentTemplates(
                    schema.fragmentFrom("fragment Input on Item { source { token } echo(value: ${'$'}remote) callback: echo(value: ${'$'}provided) }", variableTarget = target).materializeSelections,
                    schema.fragmentFrom("fragment Input on Query { source { token } echo(value: ${'$'}local) callback: echo(value: ${'$'}provided) }", variableTarget = target).materializeSelections,
                    allVariables,
                    variablesProvider = { mapOf("provided" to 3) },
                )
                mapOf(item to TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef(), mapOf("input" to pair)) { _, _ -> CheckerResult.Success })
            },
        )
        val world = testWorld.newAssumptions(selectiveResolvers = true)
        val fragment = testWorld.schemas.fragmentFrom("fragment Test on Query { item { __typename } }")
        val recorder = CheckerApplicationRecorder()
        val subject = observeResolution(world, world.objectOf("Query"), fragment.subselections, checkerObserver = recorder)
        return GeneratedResolutionObservation(subject.operation, fragment, subject, recorder.checkerApplications())
    }

    private val variableSignatures = setOf(
        GeneratedTypeCheckerSignature.FROM_PROVIDER_VARIABLE,
        GeneratedTypeCheckerSignature.PROVIDER_VARIABLE_IN_OBJECT_INPUT,
        GeneratedTypeCheckerSignature.PROVIDER_VARIABLE_IN_QUERY_INPUT,
        GeneratedTypeCheckerSignature.FROM_OBJECT_PATH_VARIABLE,
        GeneratedTypeCheckerSignature.FROM_QUERY_PATH_VARIABLE,
        GeneratedTypeCheckerSignature.PATH_VARIABLE_IN_OBJECT_INPUT,
        GeneratedTypeCheckerSignature.PATH_VARIABLE_IN_QUERY_INPUT,
        GeneratedTypeCheckerSignature.NESTED_PATH_VARIABLE,
    )
}
