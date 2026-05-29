package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.of
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.NodeResolver.Instrumented
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.NodeResolverExecutor

/**
 * Generate resolvers for Node implementations in the provided schema,
 * capable of resolving their own output selection set.
 *
 * This generated [NodeResolverExecutor]s will be for any Node implementations
 * with the `@resolver` directive, or for Nodes without `@resolver` if
 * [UndeclaredNodeResolverWeight] is configured.
 */
fun Arb.Companion.nodeResolverExecutor(
    schema: ViaductSchema,
    cfg: Config = Config.default
): Arb<NodeResolverExecutor> =
    arbitrary { rs ->
        val env = ViaductGenEnv(schema, cfg, rs)
        val typeName = Arb.of(env.resolverCoordinates.nodeResolvers).bind()
        env.nodeResolverExecutorGen.gen(typeName)
    }

/**
 * Generate resolvers for the specified node type in the provided schema.
 *
 * Resolvers produced by this generator will always resolve their own output selection set.
 */
fun Arb.Companion.nodeResolverExecutor(
    schema: ViaductSchema,
    typeName: String,
    cfg: Config = Config.default
): Arb<NodeResolverExecutor> {
    require(typeName in schema.nodeImpls)

    return arbitrary { rs ->
        val env = ViaductGenEnv(schema, cfg, rs)
        env.nodeResolverExecutorGen.gen(typeName)
    }
}

internal fun interface NodeResolverExecutorGen {
    fun gen(typeName: String): NodeResolverExecutor

    companion object {
        operator fun invoke(env: ViaductGenEnv): NodeResolverExecutorGen =
            NodeResolverExecutorGen { typeName ->
                val isSelective = env.rs.sampleWeight(env.cfg[SelectiveResolverWeight])
                val nodeResolver = env.fork().let { env ->
                    env.cfg[NodeResolverFactory]
                        .createNodeResolver(
                            NodeResolver.Factory.Params(
                                env.schemas.viaductSchema,
                                env.nodeResolverValueGen,
                                env.resolverCoordinates,
                                typeName,
                                isSelective,
                                env.cfg,
                                env.rs,
                            )
                        )
                }
                NodeResolverExecutorImpl(
                    typeName,
                    isSelective,
                    nodeResolver,
                )
            }
    }
}

private class NodeResolverExecutorImpl(
    override val typeName: String,
    override val isSelective: Boolean,
    private val nodeResolver: NodeResolver,
) : NodeResolverExecutor {
    override val metadata = ResolverMetadata.forModern(typeName)
    override val isBatching: Boolean = false

    override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> =
        selectors.associateWith { sel ->
            runCatching {
                nodeResolver(sel, context)
            }
        }
}

fun interface NodeResolver {
    suspend operator fun invoke(
        selector: NodeResolverExecutor.Selector,
        ctx: EngineExecutionContext
    ): EngineObjectData

    /**
     * A [NodeResolver] wrapper that records every invocation via [recorder].
     * Use in tests to assert that a resolver was called and to inspect the arguments it received.
     */
    class Instrumented(val underlying: NodeResolver) : NodeResolver {
        /** The arguments passed to a single invocation. */
        data class Args(
            val selector: NodeResolverExecutor.Selector,
            val ctx: EngineExecutionContext
        )

        val recorder = CallRecorder { args: Args -> underlying(args.selector, args.ctx) }

        override suspend fun invoke(
            selector: NodeResolverExecutor.Selector,
            ctx: EngineExecutionContext
        ): EngineObjectData = recorder(Args(selector, ctx))
    }

    interface Factory {
        data class Params(
            val schema: ViaductSchema,
            val nodeResolverValueGen: NodeResolverValueGen,
            val resolverCoordinates: ResolverCoordinates,
            val typeName: String,
            val selective: Boolean,
            val cfg: Config,
            val random: RandomSource
        )

        fun createNodeResolver(params: Params): NodeResolver

        object Arbitrary : Factory {
            override fun createNodeResolver(params: Params): NodeResolver = Resolver(params)

            internal class Resolver(val params: Params) : NodeResolver {
                override suspend fun invoke(
                    selector: NodeResolverExecutor.Selector,
                    ctx: EngineExecutionContext
                ): EngineObjectData {
                    params.random.maybeDelay(params.cfg[ResolverLatencyMillis])
                    maybeThrowResolverException(params.cfg, NodeResolverExceptionWeight, params.random)

                    return params.nodeResolverValueGen.gen(
                        params.typeName,
                        params.selective,
                        selector.selections,
                        EngineCtx(ctx)
                    )
                }
            }
        }

        class Instrumented(val underlying: Factory = Arbitrary) : Factory {
            val resolvers = mutableMapOf<String, NodeResolver.Instrumented>()

            fun resolver(typeName: String): NodeResolver.Instrumented = requireNotNull(resolvers[typeName])

            val recorder = CallRecorder.sync { params: Params ->
                val resolver = Instrumented(
                    underlying.createNodeResolver(params)
                )
                resolvers[params.typeName] = resolver
                resolver
            }

            override fun createNodeResolver(params: Params): NodeResolver.Instrumented = recorder(params)
        }
    }
}
