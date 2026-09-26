package semantics.correctresolution

import java.util.Collections
import kotlin.test.assertEquals
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.SelectionForest
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import semantics.resolvers.resolver02.resolve as resolve02
import semantics.resolvers.resolver03.resolve as resolve03
import semantics.resolvers.resolver07.resolve as resolve07
import semantics.resolvers.resolver08.resolve as resolve08
import semantics.shared.ResolverInvocationObservation
import semantics.contract.selectionValues
import semantics.shared.SharedOperationContext

/** Existing recorder subclasses must continue receiving the documented compatibility callback. */
class QueryObserverCompatibilityTest {
    @TestFactory
    fun `recorder subclasses retain two argument Query preparation callback`() =
        listOf(
            Subject("Resolver02", false) { selections -> resolve02(selections) },
            Subject("Resolver03", true) { selections -> resolve03(selections) },
            Subject("Resolver07", false) { selections -> resolve07(selections) },
            Subject("Resolver08", true) { selections -> resolve08(selections) },
        ).map { subject ->
            dynamicTest(subject.name) {
                val events = Collections.synchronizedList(mutableListOf<String>())
                val observer = object : CorrectnessResolverObserver() {
                    override fun onQueryFragmentPrepared(
                        resolverOccurrenceId: ResolverOccurrenceId,
                        result: ObjectEngineResult,
                    ) {
                        super.onQueryFragmentPrepared(resolverOccurrenceId, result)
                        events += "prepared"
                    }

                    override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                        super.onResolverInvocation(observation)
                        events += observation.field.name
                    }
                }
                val world = TestWorld.fromSDL(
                    selectiveResolvers = subject.selective,
                    schemaSDL = "type Query { consumer: Int! dependency: Int! }",
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.requireObjectField("Query", "consumer") to fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                                schema.fragmentFrom("fragment Input on Query { dependency }"),
                            ) { _, query, _ -> query.selectionValues().getValue("dependency") },
                            schema.requireObjectField("Query", "dependency") to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        )
                    },
                ).assumptions
                val operation = SharedOperationContext.create(world, resolverObserver = observer)
                subject.resolve(operation, world.fragmentFrom("fragment Result on Query { consumer }").subselections)
                assertEquals(1, observer.allQueryFragmentResults().size, "Recorder still captured the association")
                assertEquals(listOf("prepared", "dependency", "consumer"), events)
            }
        }

    private class Subject(
        val name: String,
        val selective: Boolean,
        val resolve: SharedOperationContext<*>.(SelectionForest) -> ObjectEngineResult,
    )
}
