package viaduct.engine.runtime2.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.runtime.execution.NodeResolverContract
import viaduct.graphql.test.assertJson

@ExperimentalCoroutinesApi
class Engine2NodeResolverContractTest : NodeResolverContract, Engine2QueryExecutionContract {
    @Test
    @Disabled("ALT: Production permits a Node-valued field resolver to materialize the Node directly; qplan requires every Node value to cross its node resolver")
    override fun `node reference nested inside resolver response`() {
        super<NodeResolverContract>.`node reference nested inside resolver response`()
    }

    @Test
    @Disabled("N/A: Tests production's pending-OER/Dispatcher scheduling policy, not resolver correctness.")
    override fun `node field executes in parallel with node resolver`() {
        super<NodeResolverContract>.`node field executes in parallel with node resolver`()
    }

    @Test
    @Disabled("N/A: Tests request-scoped NodeDataLoader caching; production already marks it flaky.")
    override fun `node resolver reads from dataloader cache`() {
        super<NodeResolverContract>.`node resolver reads from dataloader cache`()
    }

    @Test
    @Disabled("N/A: Tests production's non-selective data-loader cache-key policy.")
    override fun `non-selective node resolver reads from dataloader cache for different selection sets`() {
        super<NodeResolverContract>.`non-selective node resolver reads from dataloader cache for different selection sets`()
    }

    @Test
    @Disabled("N/A: Tests selective data-loader cache coverage and cache-key policy, not qplan resolution semantics.")
    override fun `selective node resolver does not read from dataloader cache if selection set does not cover`() {
        super<NodeResolverContract>.`selective node resolver does not read from dataloader cache if selection set does not cover`()
    }

    @Test
    @Disabled("N/A: Tests memoization of query-fragment OERs across resolvers, which qplan will not support")
    override fun `node resolver not executed twice for the same query path`() {
        super<NodeResolverContract>.`node resolver not executed twice for the same query path`()
    }

    @Test
    fun `ALTERNATIVE node reference nested inside resolver response`() {
        runQueryTest(
            EngineTestModule(schemaSDL) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            ctx.createNodeReference(
                                "1",
                                requireNotNull(schema.schema.getObjectType("Baz")),
                            )
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, ctx ->
                        createEngineObjectData(
                            objectType,
                            when (id) {
                                "1" ->
                                    mapOf(
                                        "x" to 1,
                                        "anotherBaz" to
                                            ctx.createNodeReference(
                                                "2",
                                                requireNotNull(schema.schema.getObjectType("Baz")),
                                            ),
                                    )
                                else -> mapOf("x" to 99)
                            },
                        )
                    }
                }
            }
        ) { runQuery ->
            runQuery("{baz { x anotherBaz { x } }}")
                .assertJson("""{"data": {"baz": {"x": 1, "anotherBaz": {"x": 99}}}}""")
        }
    }
}
