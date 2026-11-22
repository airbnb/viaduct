package viaduct.engine.runtime2.correctresolution

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import viaduct.engine.runtime2.arbitrary.BoundedRecorder
import viaduct.engine.runtime2.arbitrary.FieldCoordinate
import viaduct.engine.runtime2.arbitrary.ResolutionOccurrenceWitness
import viaduct.engine.runtime2.arbitrary.ResolverOccurrenceApplicationRecord
import viaduct.engine.runtime2.contract.registeredResolverOccurrenceApplicationIdentityCounts
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.ResolutionDispatcherResource
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver08.resolve

/** A malformed scope association must not bless cross-occurrence sharing and omitted work. */
class QueryScopeOwnershipTest : ResolutionDispatcherResource {
    @Test
    fun `resolver08 ownership oracle rejects coalesced scopes despite a reused scope token`() {
        verifyOwnershipOracle { operation, selection -> operation.resolve(selection) }
    }

    @Test
    fun `production ownership oracle rejects coalesced scopes despite a reused scope token`() {
        verifyOwnershipOracle { operation, selection -> operation.resolveWithTestDispatcher(selection) }
    }

    private fun verifyOwnershipOracle(resolve: (SharedOperationContext<*>, SelectionForest) -> ObjectEngineResult) {
        val events = Collections.synchronizedList(mutableListOf<ResolverInvocationObservation>())
        val scopes = Collections.synchronizedList(mutableListOf<ScopeEvent>())
        val addresses = Collections.synchronizedList(mutableListOf<AddressEvent>())
        val recorder = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                events += observation
            }

            override val onQueryFragmentPrepared: (ResolverOccurrenceId, ObjectEngineResult, OEROccurrence?) -> Unit = { resolverOccurrenceId, result, owningOccurrence ->
                super.onQueryFragmentPrepared(resolverOccurrenceId, result, owningOccurrence)
                if (owningOccurrence != null) scopes += ScopeEvent(resolverOccurrenceId, result, owningOccurrence)
            }

            override val onQueryFragmentOwnerAddress: (ResolverOccurrenceId, OEROccurrence, ObjectEngineResult.ObjectKey) -> Unit = { resolverOccurrenceId, resolverOER, resolverKey ->
                super.onQueryFragmentOwnerAddress(resolverOccurrenceId, resolverOER, resolverKey)
                addresses += AddressEvent(resolverOccurrenceId, resolverOER, resolverKey)
            }
        }
        val worldFixture = TestWorld.fromDSL(
            """
            extend type Query {
              items: [Item!]! @resolver(script: "[{:}, {:}]")
              source: Int! @resolver(script: "7")
            }
            type Item { value: Int! @resolver(qf: "source", script: "queryValue.source") }
            """.trimIndent(),
        )
        val world = worldFixture.assumptions
        val selection = worldFixture.schemas.fragmentFrom("fragment Result on Query { items { value } }").subselections
        val operation = SharedOperationContext.create(world, resolutionObserver = recorder)
        val result = resolve(operation, selection)
        assertEquals(2, scopes.size)
        val first = scopes.first()
        val second = scopes.last()
        assertNotSame(first.scope, second.scope)
        assertNotSame(first.result, second.result)
        assertTrue(result.correctResolution(operation, selection.merge(world.schema.requireQueryTypeDef())))
        assertEquals(5, result.registeredResolverOccurrenceApplicationIdentityCounts(operation).values.sum())

        // Simulate a broken implementation that caches the first scope and reports that stale
        // owning occurrence for the second list element, omitting its source application entirely.
        val malformed = CorrectnessResolverObserver()
        malformed.recordQueryOERPrepared(requireNotNull(recorder.queryOER(first.result)), null, 1)
        scopes.forEach { event ->
            malformed.onQueryFragmentPrepared(event.owner, first.result, first.scope)
            val address = addresses.single { it.owner == event.owner }
            malformed.onQueryFragmentOwnerAddress(address.owner, address.resolverOER, address.resolverKey)
        }
        val omittedOwner = ResolverOccurrenceId.at(second.result, listOf(second.result.keys.single()))
        val mutantLog = BoundedRecorder<ResolverOccurrenceApplicationRecord>()
        events.filter { it.resolverOccurrenceId != omittedOwner }.forEach { event ->
            malformed.onResolverInvocation(event)
            mutantLog.record(
                ResolverOccurrenceApplicationRecord.capture(
                    resolverOccurrenceId = event.resolverOccurrenceId,
                    occurrencePath = event.occurrencePath,
                    field = FieldCoordinate(event.field.containingDef.name, event.field.name),
                    arguments = event.arguments,
                    input = event.input,
                    suppliedDemand = event.suppliedDemand,
                ),
            )
        }
        assertEquals(4, mutantLog.snapshot().size)
        val mutantOperation = SharedOperationContext.create(world, resolutionObserver = malformed)
        val accepted = try {
            result.correctResolution(mutantOperation, selection.merge(world.schema.requireQueryTypeDef())) &&
                result.registeredResolverOccurrenceApplicationIdentityCounts(mutantOperation) ==
                ResolutionOccurrenceWitness(mutantLog.snapshot()).applicationIdentityCounts()
        } catch (rejected: IllegalStateException) {
            false
        }
        assertFalse(
            accepted,
            "The value and exact-occurrence oracles must reject sharing between different list-element owners even if scope metadata is stale",
        )
    }

    private class ScopeEvent(
        val owner: ResolverOccurrenceId,
        val result: ObjectEngineResult,
        val scope: OEROccurrence,
    )

    private class AddressEvent(
        val owner: ResolverOccurrenceId,
        val resolverOER: OEROccurrence,
        val resolverKey: ObjectEngineResult.ObjectKey,
    )
}
