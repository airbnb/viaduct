package semantics.resolvers.resolver01

import kotlin.test.assertFailsWith
import model.ObjectEngineResult
import model.fragmentFrom
import model.requireType
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

class DepthFirstOrchestrationTaskTest {
    @Test
    fun `orchestration tasks validate source and target types at construction`() {
        val world =
            TestWorld.fromSDL(
                """
                type Query {
                  item: Item
                }

                type Item {
                  value: Int
                }
                """.trimIndent(),
            ).assumptions
        val source = world.resolverRegistry.createRootQueryInput()
        val target =
            ObjectEngineResult.of(
                world.schema.requireType("Item") as ViaductSchema.Object,
                mutable = true,
            )

        assertFailsWith<IllegalArgumentException> {
            DepthFirstOrchestrationTask.create(
                operation = DepthFirstOperationContext(SharedOperationContext.create(world), { it }, DepthFirstTaskDispatcher()),
                occurrence = OEROccurrence(target, emptyList(), target),
                source = source,
                constructionDemand =
                    world
                        .fragmentFrom("fragment ignored on Query { __typename }")
                        .subselections,
                queryOERDepth = 0,
            )
        }
    }
}
