package semantics.contract

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.ListEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.registry.FieldCheckerResolver
import model.registry.ResolverFragmentTemplates
import model.registry.TypeCheckerResolver
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import org.junit.jupiter.api.Test
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.resolvers.resolver23.ResolverGeneratedTest
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.graphql.schema.ViaductSchema

class GeneratedTypeCheckerCoverageTest {
    @Test
    fun `counts resolver backed inputs on both roots and checked dependencies beyond them`() {
        val observation = observation()
        val coverage = observation.typeCheckerCoverage()
        assertTrue(GeneratedTypeCheckerSignature.OBJECT_INPUT_RESOLVER in coverage)
        assertTrue(GeneratedTypeCheckerSignature.QUERY_INPUT_RESOLVER in coverage)
        assertTrue(GeneratedTypeCheckerSignature.RESOLVER_INPUT_FIELD_CHECK in coverage)
        assertTrue(GeneratedTypeCheckerSignature.RESOLVER_INPUT_TYPE_CHECK in coverage)
        assertTrue(GeneratedTypeCheckerSignature.SUCCESS in coverage)
        assertTrue(GeneratedTypeCheckerSignature.DENIAL in coverage)
        assertTrue(GeneratedTypeCheckerSignature.SUCCESS_WITH_RESOLVER_INPUT in coverage)
        assertFalse(GeneratedTypeCheckerSignature.DENIAL_WITH_RESOLVER_INPUT in coverage)
        assertTrue(GeneratedTypeCheckerSignature.FIELD_AND_TYPE_SAME_PATH in coverage)
    }

    @Test
    fun `registered dependencies without runtime invocations do not count as activation`() {
        val observation = observation().withResolverObserver(CorrectnessResolverObserver())
        assertNoResolverCoverage(observation)
    }

    @Test
    fun `same resolver paths on another Query root do not count as activation`() {
        val observation = observation()
        val recorder = CorrectnessResolverObserver()
        val original = observation.operation.resolverObserver as CorrectnessResolverObserver
        val anotherRoot = observation().result
        original.allResolverInvocations().forEach { invocation ->
            recorder.onResolverInvocation(invocation.copy(resolverOccurrenceId = ResolverOccurrenceId.at(anotherRoot, invocation.occurrencePath)))
        }
        assertNoResolverCoverage(observation.withResolverObserver(recorder))
    }

    @Test
    fun `resolver evidence at another path does not count as activation`() {
        val observation = observation()
        val recorder = CorrectnessResolverObserver()
        val original = observation.operation.resolverObserver as CorrectnessResolverObserver
        original.allResolverInvocations().forEach { invocation ->
            recorder.onResolverInvocation(
                invocation.copy(
                    resolverOccurrenceId = ResolverOccurrenceId.at(observation.result, invocation.occurrencePath + ListEngineResult.Index.of(7)),
                )
            )
        }
        assertNoResolverCoverage(observation.withResolverObserver(recorder))
    }

    private fun assertNoResolverCoverage(observation: GeneratedResolutionObservation) {
        val coverage = observation.typeCheckerCoverage()
        assertTrue(GeneratedTypeCheckerSignature.TYPE_CHECKER in coverage)
        assertFalse(GeneratedTypeCheckerSignature.OBJECT_INPUT_RESOLVER in coverage)
        assertFalse(GeneratedTypeCheckerSignature.QUERY_INPUT_RESOLVER in coverage)
        assertFalse(GeneratedTypeCheckerSignature.RESOLVER_INPUT_FIELD_CHECK in coverage)
        assertFalse(GeneratedTypeCheckerSignature.RESOLVER_INPUT_TYPE_CHECK in coverage)
    }

    private fun GeneratedResolutionObservation.withResolverObserver(observer: CorrectnessResolverObserver): GeneratedResolutionObservation =
        copy(operation = SharedOperationContext.create(world, resolverObserver = observer, checkerObserver = operation.checkerObserver))

    private fun observation(): GeneratedResolutionObservation {
        val world = TestWorld.fromSDL(
            schemaSDL = """
                type Query { item: Item! source: Dependency! probe: Int! }
                type Item { value: Int! active: Int! dependency: Dependency! }
                type Dependency { value: Int! }
            """.trimIndent(),
            fieldResolvers = { schema ->
                mapOf(
                    schema.requireObjectField("Query", "item") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                        schema.objectOf("Item") {
                            "value" setTo 1
                            "dependency" setTo schema.objectOf("Dependency") { "value" setTo 2 }
                        }
                    },
                    schema.requireObjectField("Query", "source") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                        schema.objectOf("Dependency") { "value" setTo 3 }
                    },
                    schema.requireObjectField("Item", "active") to fieldResolverOf(schema.fragmentFrom("fragment Input on Item { dependency { value } }")) { _, _ -> 4 },
                    schema.requireObjectField("Query", "probe") to fieldResolverOf(schema.fragmentFrom("fragment Input on Query { source { value } }")) { _, _ -> 5 },
                )
            },
            typeCheckers = { schema ->
                val item = schema.requireType("Item") as ViaductSchema.Object
                val dependency = schema.requireType("Dependency") as ViaductSchema.Object
                mapOf(
                    item to TypeCheckerResolver.of(
                        item,
                        schema.requireQueryTypeDef(),
                        mapOf(
                            "input" to ResolverFragmentTemplates(
                                schema.fragmentFrom("fragment Input on Item { active }").materializeSelections,
                                schema.fragmentFrom("fragment Input on Query { probe }").materializeSelections,
                            ),
                        )
                    ) { _, _ -> CheckerResult.Success },
                    dependency to TypeCheckerResolver.of(dependency, schema.requireQueryTypeDef()) { _, _ -> Denial },
                )
            },
            fieldCheckers = { schema ->
                listOf("Query" to "item", "Dependency" to "value").associate { (type, name) ->
                    val field = schema.requireObjectField(type, name)
                    field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef()) { _, _, _ -> CheckerResult.Success }
                }
            },
        ).newAssumptions(selectiveResolvers = true)
        val fragment = world.fragmentFrom("fragment Test on Query { item { value } }")
        val recorder = CheckerApplicationRecorder()
        val subject = ResolverGeneratedTest().observeResolution(world, world.objectOf("Query"), fragment.subselections, checkerObserver = recorder)
        return GeneratedResolutionObservation(subject.operation, fragment, subject, recorder.checkerApplications())
    }

    private object Denial : CheckerResult.Error {
        override val error = IllegalStateException("denied")

        override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

        override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
    }
}
