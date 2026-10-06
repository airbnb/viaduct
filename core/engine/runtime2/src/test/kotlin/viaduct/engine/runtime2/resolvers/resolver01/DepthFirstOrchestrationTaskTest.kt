package viaduct.engine.runtime2.resolvers.resolver01

import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

class DepthFirstOrchestrationTaskTest {
    @Test
    fun `orchestration tasks validate source and target types at construction`() {
        val worldFixture =
            TestWorld.fromSDL(
                """
                type Query {
                  item: Item
                }

                type Item {
                  value: Int
                }
                """.trimIndent(),
            )
        val world = worldFixture.assumptions
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
                    worldFixture.schemas.fragmentFrom("fragment ignored on Query { __typename }")
                        .subselections,
                queryOERDepth = 0,
            )
        }
    }
}
