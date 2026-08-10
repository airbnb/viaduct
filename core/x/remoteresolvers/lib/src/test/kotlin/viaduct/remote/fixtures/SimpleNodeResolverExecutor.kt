package viaduct.remote.fixtures

import graphql.schema.GraphQLObjectType
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineObjectDataBuilder
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.spi.NodeResolverExecutor

/**
 * Simple in-memory NodeResolverExecutor for testing.
 *
 * This test fixture maintains a map of node IDs to their data and resolves
 * them by looking up in the map and building EngineObjectData.
 */
class SimpleNodeResolverExecutor(
    override val typeName: String,
    private val nodeData: Map<String, Map<String, Any?>>
) : NodeResolverExecutor {
    override val isBatching: Boolean = false
    override val isSelective: Boolean = false
    override val metadata: ResolverMetadata = ResolverMetadata.forMock("SimpleNodeResolverExecutor:$typeName")

    /**
     * An object type in the schema other than this resolver's own, for [UNDECODABLE_NODE_ID]. Chosen by
     * name so the fixture behaves the same regardless of schema iteration order.
     */
    private fun wrongType(context: EngineExecutionContext): GraphQLObjectType =
        context.fullSchema.schema.allTypesAsList
            .filterIsInstance<GraphQLObjectType>()
            .filter { it.name != typeName && !it.name.startsWith("__") }
            .minByOrNull { it.name }
            ?: throw IllegalStateException("Schema has no object type other than $typeName")

    override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        // Get the GraphQL type for this resolver
        val graphQLType = context.fullSchema.schema.getObjectType(typeName)
            ?: throw IllegalStateException("Type $typeName not found in schema")

        // Resolve each selector
        return selectors.associateWith { selector ->
            when {
                // Sentinel: return a node whose value carries an unresolved nested NodeReference.
                // EngineObjectDataSerializer.serialize rejects a nested NodeReference (encodeValue
                // throws), so this node fails serialization on the RRS side while its batch-mates —
                // which take the normal path below — still serialize and return successfully.
                selector.id == UNSERIALIZABLE_NODE_ID -> Result.success(
                    EngineObjectDataBuilder.from(graphQLType)
                        .put("id", selector.id)
                        .put("friend", context.createNodeReference(selector.id, graphQLType))
                        .build()
                )
                // Sentinel: return a node of the WRONG GraphQL type. It serializes cleanly on the RRS
                // side, so the failure lands on the caller's deserialize — which asserts the payload's
                // type against the node type it independently expects. Exercises per-node isolation of
                // a *deserialization* failure, the mirror of the serialize case above.
                selector.id == UNDECODABLE_NODE_ID -> Result.success(
                    EngineObjectDataBuilder.from(wrongType(context)).build()
                )
                else -> {
                    val data = nodeData[selector.id]
                    if (data != null) {
                        Result.success(
                            EngineObjectDataBuilder.from(graphQLType).apply {
                                data.forEach { (key, value) -> put(key, value) }
                            }.build()
                        )
                    } else {
                        Result.failure(NoSuchElementException("Node not found: ${selector.id}"))
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Node id that makes [resolve] return a node whose serialization throws: its value carries an
         * unresolved nested [viaduct.engine.api.NodeReference], which [EngineObjectData] serialization
         * refuses to encode. Used to exercise per-node serialization-failure isolation in the
         * remote-resolver batch path (a bad node must not sink its batch-mates).
         */
        const val UNSERIALIZABLE_NODE_ID = "user:unserializable"

        /**
         * Node id that makes [resolve] return a well-formed node of a *different* GraphQL type. The RRS
         * serializes it happily; the caller rejects it because the payload's declared type disagrees
         * with the node type the caller expects. Used to exercise per-node isolation of a
         * deserialization failure (a bad node must not sink its batch-mates).
         */
        const val UNDECODABLE_NODE_ID = "user:undecodable"

        /**
         * Creates a resolver with sample user data for testing.
         */
        fun createUserResolver(): SimpleNodeResolverExecutor {
            return SimpleNodeResolverExecutor(
                typeName = "User",
                nodeData = mapOf(
                    "user:1" to mapOf(
                        "id" to "user:1",
                        "name" to "Alice",
                        "email" to "alice@example.com"
                    ),
                    "user:2" to mapOf(
                        "id" to "user:2",
                        "name" to "Bob",
                        "email" to "bob@example.com"
                    ),
                    "user:3" to mapOf(
                        "id" to "user:3",
                        "name" to "Charlie",
                        "email" to "charlie@example.com"
                    )
                )
            )
        }

        /**
         * Creates a resolver with sample post data for testing.
         */
        fun createPostResolver(): SimpleNodeResolverExecutor {
            return SimpleNodeResolverExecutor(
                typeName = "Post",
                nodeData = mapOf(
                    "post:1" to mapOf(
                        "id" to "post:1",
                        "title" to "Hello World",
                        "content" to "This is my first post!",
                        "authorId" to "user:1"
                    ),
                    "post:2" to mapOf(
                        "id" to "post:2",
                        "title" to "GraphQL is Great",
                        "content" to "Remote resolvers are interesting...",
                        "authorId" to "user:2"
                    )
                )
            )
        }
    }
}

/**
 * A NodeResolverExecutor that makes callback queries to test re-entrant calls.
 *
 * This resolver fetches a Post and then makes a callback query to fetch
 * the associated User (author), demonstrating the callback flow where
 * RRS → gRPC callback → RRP → engine.
 */
class CallbackNodeResolverExecutor(
    private val postData: Map<String, Map<String, Any?>>
) : NodeResolverExecutor {
    override val typeName: String = "Post"
    override val isBatching: Boolean = false
    override val isSelective: Boolean = false
    override val metadata: ResolverMetadata = ResolverMetadata.forMock("CallbackNodeResolverExecutor:Post")

    override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        val graphQLType = context.fullSchema.schema.getObjectType(typeName)
            ?: throw IllegalStateException("Type $typeName not found in schema")

        return selectors.associateWith { selector ->
            val data = postData[selector.id]
            if (data != null) {
                try {
                    // Build post data
                    val builder = EngineObjectDataBuilder.from(graphQLType)
                    data.forEach { (key, value) -> builder.put(key, value) }

                    // Make callback query to fetch the author (User)
                    // This tests the re-entrant call: RRS → callback → RRP → engine
                    val authorId = data["authorId"] as? String
                    if (authorId != null) {
                        val authorSelections = context.engineSelectionSetFactory.engineSelectionSet(
                            "User",
                            "id name email",
                            emptyMap()
                        )
                        val authorData = context.resolveSelectionSet(authorSelections)

                        // Add author data to the post
                        builder.put("author", authorData)
                    }

                    Result.success(builder.build())
                } catch (e: Exception) {
                    Result.failure(e)
                }
            } else {
                Result.failure(NoSuchElementException("Post not found: ${selector.id}"))
            }
        }
    }

    companion object {
        /**
         * Creates a resolver that makes callback queries.
         */
        fun create(): CallbackNodeResolverExecutor {
            return CallbackNodeResolverExecutor(
                postData = mapOf(
                    "post:1" to mapOf(
                        "id" to "post:1",
                        "title" to "Hello World",
                        "content" to "This is my first post!",
                        "authorId" to "user:1"
                    ),
                    "post:2" to mapOf(
                        "id" to "post:2",
                        "title" to "GraphQL is Great",
                        "content" to "Remote resolvers are interesting...",
                        "authorId" to "user:2"
                    )
                )
            )
        }
    }
}
