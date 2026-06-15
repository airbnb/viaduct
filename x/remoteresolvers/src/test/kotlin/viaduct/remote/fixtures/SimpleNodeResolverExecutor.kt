package viaduct.remote.fixtures

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

    override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        // Get the GraphQL type for this resolver
        val graphQLType = context.fullSchema.schema.getObjectType(typeName)
            ?: throw IllegalStateException("Type $typeName not found in schema")

        // Resolve each selector
        return selectors.associateWith { selector ->
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

    companion object {
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
                        val authorData = context.resolveSelectionSetSync(authorSelections)

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
