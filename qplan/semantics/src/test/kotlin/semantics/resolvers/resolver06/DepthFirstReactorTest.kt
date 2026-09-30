package semantics.resolvers.resolver06

import java.util.PriorityQueue
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import model.ObjectEngineResult
import model.fragmentFrom
import model.merge
import model.requireQueryTypeDef
import model.schemaType
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import semantics.resolvers.GroundedFieldPublicationOccurrence
import semantics.resolvers.resolver01.DepthFirstFieldResolverTask
import semantics.resolvers.resolver01.DepthFirstOperationContext
import semantics.resolvers.resolver01.DepthFirstOrchestrationTask
import semantics.resolvers.resolver01.DepthFirstTaskDispatcher
import semantics.shared.OEROccurrence
import semantics.shared.SharedOERContext
import semantics.shared.SharedOperationContext

class DepthFirstReactorTest {
    @Test
    fun `Query OER depth precedes task kind and equal-depth insertion order`() {
        val worldFixture =
            TestWorld.fromSDL(
                schemaSDL = "type Query { value: Int }",
                selectiveResolvers = false,
            )
        val world = worldFixture.assumptions
        val source = world.resolverRegistry.createRootQueryInput()
        val selections =
            worldFixture.schemas.fragmentFrom("fragment ignored on Query { __typename }")
                .subselections
        val sourceType = source.schemaType
        val selection = selections.merge(sourceType).byGroundKey().values.single()
        val target = ObjectEngineResult.of(sourceType, emptyMap(), mutable = true)
        val operation = DepthFirstOperationContext(SharedOperationContext.create(world), { it }, DepthFirstTaskDispatcher())
        val occurrence = OEROccurrence(target, emptyList(), target)
        val firstResolver =
            DepthFirstFieldResolverTask.prepare(
                GroundedFieldPublicationOccurrence(
                    operation,
                    occurrence,
                    selection,
                    target.reserveCell(selection.key),
                    queryOER = SharedOERContext.undemandedQuery(world.schema.requireQueryTypeDef()),
                ),
                queryOERDepth = 0,
            )
        val secondResolver =
            DepthFirstFieldResolverTask.prepare(
                GroundedFieldPublicationOccurrence(
                    operation,
                    occurrence,
                    selection,
                    ObjectEngineResult.of(sourceType, mutable = true).reserveCell(selection.key),
                    queryOER = SharedOERContext.undemandedQuery(world.schema.requireQueryTypeDef()),
                ),
                queryOERDepth = 0,
            )
        val queryResolver =
            DepthFirstFieldResolverTask.prepare(
                GroundedFieldPublicationOccurrence(
                    operation,
                    occurrence,
                    selection,
                    ObjectEngineResult.of(sourceType, mutable = true).reserveCell(selection.key),
                    queryOER = SharedOERContext.undemandedQuery(world.schema.requireQueryTypeDef()),
                ),
                queryOERDepth = 1,
            )
        val orchestration =
            DepthFirstOrchestrationTask.create(
                operation,
                occurrence,
                source,
                selections,
                queryOERDepth = 0,
            )
        assertSame(operation, orchestration.operation)
        val publication = firstResolver.publication
        assertSame(operation, publication.operation)
        assertSame(operation.world, publication.world)
        assertSame(operation.variableBindings, publication.variableBindings)
        assertSame(operation.resolverObserver, publication.resolverObserver)
        assertSame(operation.dispatcher, publication.dispatcher)
        val tasks = PriorityQueue(depthFirstTaskComparator)

        tasks += ScheduledTask(orchestration, sequence = 0)
        tasks += ScheduledTask(firstResolver, sequence = 1)
        tasks += ScheduledTask(secondResolver, sequence = 2)
        tasks += ScheduledTask(queryResolver, sequence = 3)

        assertSame(queryResolver, tasks.remove().task)
        assertSame(firstResolver, tasks.remove().task)
        assertSame(secondResolver, tasks.remove().task)
        assertSame(orchestration, tasks.remove().task)
    }

    @Test
    fun `resolve can only be called once`() {
        val worldFixture =
            TestWorld.fromSDL(
                schemaSDL = "type Query { value: Int }",
                selectiveResolvers = false,
            )
        val world = worldFixture.assumptions
        val selections =
            worldFixture.schemas.fragmentFrom("fragment ignored on Query { __typename }")
                .subselections
        val reactor =
            DepthFirstReactor(
                operation = SharedOperationContext.create(world),
                complete = { demand -> demand },
                source = world.resolverRegistry.createRootQueryInput(),
                selections = selections,
            )

        reactor.resolve()

        assertFailsWith<IllegalStateException> {
            reactor.resolve()
        }
    }
}
