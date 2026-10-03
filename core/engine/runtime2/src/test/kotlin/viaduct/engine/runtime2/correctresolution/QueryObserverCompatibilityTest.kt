package viaduct.engine.runtime2.correctresolution

import java.util.Collections
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import viaduct.engine.runtime2.contract.selectionValues
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.ResolutionDispatcherResource
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver02.resolve
import viaduct.engine.runtime2.resolvers.resolver02.resolve as resolve02
import viaduct.engine.runtime2.resolvers.resolver03.resolve
import viaduct.engine.runtime2.resolvers.resolver03.resolve as resolve03
import viaduct.engine.runtime2.resolvers.resolver07.resolve
import viaduct.engine.runtime2.resolvers.resolver07.resolve as resolve07
import viaduct.engine.runtime2.resolvers.resolver08.resolve
import viaduct.engine.runtime2.resolvers.resolver08.resolve as resolve08
import viaduct.engine.runtime2.resolvers.resolver22.resolve
import viaduct.engine.runtime2.resolvers.resolver22.resolve as resolve22
import viaduct.engine.runtime2.resolvers.resolver23.resolve
import viaduct.engine.runtime2.resolvers.resolver23.resolve as resolve23

/** Existing recorder subclasses must continue receiving the documented compatibility callback. */
class QueryObserverCompatibilityTest : ResolutionDispatcherResource {
    @TestFactory
    fun `recorder subclasses retain two argument Query preparation callback`() =
        listOf(
            Subject("Resolver02", false) { selections -> resolve02(selections) },
            Subject("Resolver03", true) { selections -> resolve03(selections) },
            Subject("Resolver07", false) { selections -> resolve07(selections) },
            Subject("Resolver08", true) { selections -> resolve08(selections) },
            Subject("Resolver22", false) { selections -> resolve22(selections) },
            Subject("Resolver23", true) { selections -> resolve23(selections) },
            Subject("Resolution", true) { selections -> resolveWithTestDispatcher(selections) },
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
                val worldFixture = TestWorld.fromSDL(
                    selectiveResolvers = subject.selective,
                    schemaSDL = "type Query { consumer: Int! dependency: Int! }",
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.loweredSchema.requireObjectField("Query", "consumer") to fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Query"),
                                schema.fragmentFrom("fragment Input on Query { dependency }"),
                            ) { _, query, _ -> query.selectionValues().getValue("dependency") },
                            schema.loweredSchema.requireObjectField("Query", "dependency") to
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        )
                    },
                )
                val world = worldFixture.assumptions
                val operation = SharedOperationContext.create(world, resolverObserver = observer)
                subject.resolve(operation, worldFixture.schemas.fragmentFrom("fragment Result on Query { consumer }").subselections)
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
