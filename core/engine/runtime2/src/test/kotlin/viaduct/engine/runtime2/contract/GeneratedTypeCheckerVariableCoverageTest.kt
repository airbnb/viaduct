package viaduct.engine.runtime2.contract

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorDataReadException
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
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
        assertFalse(GeneratedTypeCheckerSignature.ERROR_ARGUMENT_IN_TYPE_INPUT in coverage)
    }

    @Test
    fun `counts argument errors in executed object type checker inputs`() {
        val coverage = observation(errorSource = ProviderFragment.QUERY).typeCheckerCoverage()
        assertTrue(GeneratedTypeCheckerSignature.ERROR_ARGUMENT_IN_TYPE_INPUT in coverage)
    }

    @Test
    fun `counts argument errors in executed Query type checker inputs`() {
        val coverage = observation(errorSource = ProviderFragment.OBJECT).typeCheckerCoverage()
        assertTrue(GeneratedTypeCheckerSignature.ERROR_ARGUMENT_IN_TYPE_INPUT in coverage)
    }

    @Test
    fun `registered argument errors without a checker invocation are not activation`() {
        ProviderFragment.entries.forEach { errorSource ->
            val observation = observation(errorSource).copy(checkerApplications = emptyList())
            assertFalse(GeneratedTypeCheckerSignature.ERROR_ARGUMENT_IN_TYPE_INPUT in observation.typeCheckerCoverage())
        }
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

    private fun observation(errorSource: ProviderFragment? = null): GeneratedResolutionObservation {
        val objectToken = if (errorSource == ProviderFragment.OBJECT) "\"ERROR\"" else "1"
        val queryToken = if (errorSource == ProviderFragment.QUERY) "\"ERROR\"" else "2"
        val testWorld = TestWorld.fromDSL(
            """
            extend type Query {
              item: Item! @resolver(result: {source: {token: $objectToken}})
              source: Source! @resolver(result: {token: $queryToken})
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
                mapOf(
                    item to TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef(), mapOf("input" to pair)) { inputs, _ ->
                        val input = inputs.getValue("input")
                        if (errorSource == ProviderFragment.QUERY) {
                            assertFailsWith<EngineErrorDataReadException> { input.objectValue.get("echo") }
                        } else {
                            assertEquals(2, input.objectValue.get("echo"))
                        }
                        if (errorSource == ProviderFragment.OBJECT) {
                            assertFailsWith<EngineErrorDataReadException> { input.queryValue.get("echo") }
                        } else {
                            assertEquals(1, input.queryValue.get("echo"))
                        }
                        assertEquals(3, input.objectValue.get("callback"))
                        assertEquals(3, input.queryValue.get("callback"))
                        CheckerResult.Success
                    },
                )
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
