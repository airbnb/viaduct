package semantics.correctresolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.ResolutionOccurrenceApplicationLog
import semantics.contract.registeredResolverOccurrenceApplicationIdentityCounts
import semantics.contract.selectionValues
import semantics.resolvers.resolver03.resolve
import semantics.shared.OEROccurrence
import semantics.shared.ResolverInvocationObservation
import semantics.shared.SharedOperationContext

/** A malformed scope association must not bless cross-occurrence sharing and omitted work. */
class QueryScopeOwnershipTest {
    @Test
    fun `ownership oracle rejects coalesced scopes despite a reused scope token`() {
        val events = mutableListOf<ResolverInvocationObservation>()
        val scopes = mutableListOf<ScopeEvent>()
        val addresses = mutableListOf<AddressEvent>()
        val recorder = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                events += observation
            }

            override fun onQueryFragmentPrepared(
                resolverOccurrenceId: ResolverOccurrenceId,
                result: ObjectEngineResult,
                owningOccurrence: OEROccurrence,
            ) {
                super.onQueryFragmentPrepared(resolverOccurrenceId, result, owningOccurrence)
                scopes += ScopeEvent(resolverOccurrenceId, result, owningOccurrence)
            }

            override fun onQueryFragmentOwnerAddress(
                resolverOccurrenceId: ResolverOccurrenceId,
                resolverOER: OEROccurrence,
                resolverKey: ObjectEngineResult.ObjectKey,
            ) {
                super.onQueryFragmentOwnerAddress(resolverOccurrenceId, resolverOER, resolverKey)
                addresses += AddressEvent(resolverOccurrenceId, resolverOER, resolverKey)
            }
        }
        val worldFixture = TestWorld.fromSDL(
            selectiveResolvers = true,
            schemaSDL = "type Query { items: [Item!]! source: Int! } type Item { value: Int! }",
            fieldResolvers = { schema ->
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "items") to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            listOf(schema.loweredSchema.objectOf("Item"), schema.loweredSchema.objectOf("Item"))
                        },
                    schema.loweredSchema.requireObjectField("Item", "value") to fieldResolverOf(
                        schema.loweredSchema.emptyFragmentOf("Item"),
                        schema.fragmentFrom("fragment Input on Query { source }"),
                    ) { _, query, _ -> query.selectionValues().getValue("source") },
                    schema.loweredSchema.requireObjectField("Query", "source") to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                )
            },
        )
        val world = worldFixture.assumptions
        val selection = worldFixture.schemas.fragmentFrom("fragment Result on Query { items { value } }").subselections
        val operation = SharedOperationContext.create(world, resolverObserver = recorder)
        val result = operation.resolve(selection)
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
        malformed.onQueryOERPrepared(requireNotNull(recorder.queryOER(first.result)), 1)
        scopes.forEach { event ->
            malformed.onQueryFragmentPrepared(event.owner, first.result, first.scope)
            val address = addresses.single { it.owner == event.owner }
            malformed.onQueryFragmentOwnerAddress(
                address.owner,
                address.resolverOER,
                address.resolverKey,
            )
        }
        val omittedOwner = ResolverOccurrenceId.at(second.result, listOf(second.result.keys.single()))
        val mutantLog = ResolutionOccurrenceApplicationLog()
        events.filter { it.resolverOccurrenceId != omittedOwner }.forEach { event ->
            malformed.onResolverInvocation(event)
            mutantLog.record(
                resolverOccurrenceId = event.resolverOccurrenceId,
                occurrencePath = event.occurrencePath,
                field = FieldCoordinate(event.field.containingDef.name, event.field.name),
                arguments = event.arguments,
                input = event.input,
                suppliedDemand = event.suppliedDemand,
            )
        }
        assertEquals(4, mutantLog.snapshot().applications.size)
        val mutantOperation = SharedOperationContext.create(world, resolverObserver = malformed)
        val accepted = try {
            result.correctResolution(mutantOperation, selection.merge(world.schema.requireQueryTypeDef())) &&
                result.registeredResolverOccurrenceApplicationIdentityCounts(mutantOperation) ==
                mutantLog.snapshot().applicationIdentityCounts()
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
