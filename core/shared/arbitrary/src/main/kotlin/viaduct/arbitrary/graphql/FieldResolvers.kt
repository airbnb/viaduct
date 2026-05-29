package viaduct.arbitrary.graphql

import graphql.schema.GraphQLObjectType
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.of
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.FieldResolver.Instrumented
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj
import viaduct.engine.api.spi.FieldResolverExecutor

/**
 * Generate resolvers for object fields in the provided schema,
 * capable of resolving their own output selection set.
 *
 * The generated [FieldResolverExecutor]s will be for any object field
 * with the `@resolver` directive, or for any object field without `@resolver`
 * if [UndeclaredFieldResolverWeight] is configured.
 *
 * FieldResolverExecutors produced by this generator may have required selection
 * sets. While this method can produce single-shot resolvers that do not
 * form cycles with themselves, it cannot guarantee that a resolver does not form invalid
 * RSS cycles with other resolvers produced by this generator.
 *
 * If many cycle-free resolvers are needed, see [Arb.Companion.viaduct]
 */
fun Arb.Companion.fieldResolverExecutor(
    schema: ViaductSchema,
    cfg: Config = Config.default
): Arb<FieldResolverExecutor> =
    arbitrary { rs ->
        val env = ViaductGenEnv(schema, cfg, rs)
        val coord = Arb.of(env.resolverCoordinates.fieldResolvers).bind()
        env.fieldResolverExecutorGen.gen(coord)
    }

/**
 * Generate resolvers for the specified coordinate in the provided schema.
 *
 * Resolvers produced by this generator will always resolve their own output selection set
 */
fun Arb.Companion.fieldResolverExecutor(
    schema: ViaductSchema,
    coord: Coordinate,
    cfg: Config = Config.default
): Arb<FieldResolverExecutor> {
    require(schema.schema.typeMap[coord.first] is GraphQLObjectType)
    require(!coord.second.startsWith("__"))
    requireNotNull(schema.schema.getFieldDefinition(coord.gj))

    return arbitrary { rs ->
        val env = ViaductGenEnv(schema, cfg, rs)
        env.fieldResolverExecutorGen.gen(coord)
    }
}

internal fun interface FieldResolverExecutorGen {
    fun gen(coord: Coordinate): FieldResolverExecutor

    companion object {
        operator fun invoke(env: ViaductGenEnv): FieldResolverExecutorGen =
            FieldResolverExecutorGen { coord ->
                val objectSelectionSet = env.requiredSelectionSetGen.gen(coord, coord.first, forChecker = false, 0)
                val querySelectionSet = env.requiredSelectionSetGen.gen(coord, env.schemas.schema.queryType.name, forChecker = false, 0)
                val isSelective = env.rs.sampleWeight(env.cfg[SelectiveResolverWeight])

                val fieldResolver = env.fork().let { env ->
                    env.cfg[FieldResolverFactory]
                        .createFieldResolver(
                            FieldResolver.Factory.Params(
                                env.schemas.viaductSchema,
                                env.fieldResolverValueGen,
                                env.resolverCoordinates,
                                isSelective,
                                env.rs.sampleWeight(env.cfg[ExerciseRequiredSelectionsWeight]),
                                coord,
                                objectSelectionSet,
                                querySelectionSet,
                                env.cfg,
                                env.rs
                            )
                        )
                }

                FieldResolverExecutorImpl(
                    coord,
                    isSelective,
                    objectSelectionSet,
                    querySelectionSet,
                    fieldResolver
                )
            }
    }
}

private class FieldResolverExecutorImpl(
    coord: Coordinate,
    override val isSelective: Boolean,
    override val objectSelectionSet: RequiredSelectionSet? = null,
    override val querySelectionSet: RequiredSelectionSet? = null,
    private val fieldResolver: FieldResolver
) : FieldResolverExecutor {
    override val resolverId: String = "${coord.first}.${coord.second}"
    override val metadata: ResolverMetadata = ResolverMetadata.forModern(resolverId)
    override val isBatching: Boolean = false

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> =
        selectors.associateWith { selector ->
            runCatching {
                fieldResolver(selector, context)
            }
        }
}

fun interface FieldResolver {
    suspend operator fun invoke(
        selector: FieldResolverExecutor.Selector,
        ctx: EngineExecutionContext
    ): Any?

    /**
     * A [FieldResolver] wrapper that records every invocation via [recorder].
     * Use in tests to assert that a resolver was called and to inspect the arguments it received.
     */
    class Instrumented(private val underlying: FieldResolver) : FieldResolver {
        /** The arguments passed to a single invocation. */
        data class Args(
            val selector: FieldResolverExecutor.Selector,
            val ctx: EngineExecutionContext
        )

        val recorder = CallRecorder { args: Args -> underlying(args.selector, args.ctx) }

        override suspend fun invoke(
            selector: FieldResolverExecutor.Selector,
            ctx: EngineExecutionContext
        ): Any? = recorder(Args(selector, ctx))
    }

    interface Factory {
        /**
         * Parameters passed to [Factory.createFieldResolver].
         *
         * @property schema The schema the resolver operates on.
         * @property fieldResolverValueGen Generates the return value for the resolved field.
         * @property resolverCoordinates All resolver coordinates in the schema.
         * @property selective If true, the resolver may omit values for some selections,
         *   simulating a resolver that does not always populate every requested field.
         * @property exerciseRequiredSelections If true, the resolver reads from its required
         *   selection sets before returning, ensuring any RSS-gated data paths are exercised.
         * @property coordinate The specific field coordinate this resolver handles.
         * @property objectSelectionSet Optional required selection set for the parent object.
         * @property querySelectionSet Optional required selection set for the query root.
         * @property cfg Arbitrary generation configuration.
         * @property rs Random source used during value generation.
         */
        data class Params(
            val schema: ViaductSchema,
            val fieldResolverValueGen: FieldResolverValueGen,
            val resolverCoordinates: ResolverCoordinates,
            val selective: Boolean,
            val exerciseRequiredSelections: Boolean,
            val coordinate: Coordinate,
            val objectSelectionSet: RequiredSelectionSet?,
            val querySelectionSet: RequiredSelectionSet?,
            val cfg: Config,
            val rs: RandomSource
        )

        fun createFieldResolver(params: Params): FieldResolver

        object Arbitrary : Factory {
            override fun createFieldResolver(params: Params): FieldResolver =
                Resolver(
                    params,
                )

            private class Resolver(val params: Params) : FieldResolver {
                override suspend fun invoke(
                    selector: FieldResolverExecutor.Selector,
                    ctx: EngineExecutionContext
                ): Any? {
                    if (params.exerciseRequiredSelections) {
                        params.objectSelectionSet?.also { rss ->
                            EngineDataExerciser.exercise(selector.objectValue, ctx, rss)
                        }
                        params.querySelectionSet?.also { rss ->
                            EngineDataExerciser.exercise(selector.queryValue, ctx, rss)
                        }
                    }

                    params.rs.maybeDelay(params.cfg[ResolverLatencyMillis])
                    maybeThrowResolverException(params.cfg, FieldResolverExceptionWeight, params.rs)

                    return params.fieldResolverValueGen.gen(
                        params.coordinate,
                        params.selective,
                        selector.selections,
                        EngineCtx(ctx)
                    )
                }
            }
        }

        class Instrumented(private val underlying: Factory = Arbitrary) : Factory {
            val resolvers = mutableMapOf<Coordinate, FieldResolver.Instrumented>()

            val recorder = CallRecorder.sync { params: Params ->
                val resolver = Instrumented(underlying.createFieldResolver(params))
                resolvers[params.coordinate] = resolver
                resolver
            }

            fun resolver(coord: Coordinate): FieldResolver.Instrumented = requireNotNull(resolvers[coord])

            override fun createFieldResolver(params: Params): FieldResolver = recorder(params)
        }
    }
}
