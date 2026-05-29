@file:OptIn(ExperimentalTime::class)

package viaduct.arbitrary.graphql

import graphql.language.VariableDefinition
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import kotlin.time.ExperimentalTime
import viaduct.api.internal.EngineValueConv
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.VariablesResolver.Instrumented
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver as EngineVariablesResolver
import viaduct.engine.api.ViaductSchema

/**
 * Generates [EngineVariablesResolver] instances for a single variable usage within a resolver.
 * For each variable, it generates an optional required selection set and delegates to the
 * configured [VariablesResolverFactory] to produce the resolver.
 */
internal fun interface VariablesResolverGen {
    fun gen(
        tfc: TypeOrFieldCoordinate,
        variableDefinition: VariableDefinition,
        forChecker: Boolean,
        depth: Int
    ): EngineVariablesResolver

    companion object {
        internal operator fun invoke(env: ViaductGenEnv): VariablesResolverGen =
            VariablesResolverGen { tfc, variableDefinition, forChecker, depth ->
                val vrRssTypeCondition = Arb.of(setOf(env.schemas.schema.queryType.name, tfc.first))
                    .next(env.rs)

                val vrRss = env.requiredSelectionSetGen.gen(tfc, vrRssTypeCondition, forChecker, depth)

                env.cfg[VariablesResolverFactory]
                    .createVariablesResolver(
                        VariablesResolver.Factory.Params(
                            tfc = tfc,
                            def = variableDefinition,
                            requiredSelectionSet = vrRss,
                            exerciseRequiredSelections = env.rs.sampleWeight(env.cfg[ExerciseRequiredSelectionsWeight]),
                            schema = env.schemas.viaductSchema,
                            cfg = env.cfg,
                            // variables resolvers can be called in a non-deterministic order by the engine.
                            // Using a forked environment ensures that the order of their invocation doesn't
                            // impact what the current rng produces
                            rs = env.rs.fork()
                        )
                    )
            }
    }
}

object VariablesResolver {
    interface Factory {
        data class Params(
            val tfc: TypeOrFieldCoordinate,
            val def: VariableDefinition,
            val requiredSelectionSet: RequiredSelectionSet?,
            val exerciseRequiredSelections: Boolean,
            val schema: ViaductSchema,
            val cfg: Config,
            val rs: RandomSource
        )

        fun createVariablesResolver(params: Params): EngineVariablesResolver

        /**
         * Default [Factory] that creates a resolver generating an arbitrary value for the
         * declared variable type. The value is derived from a seeded [RandomSource] so that
         * the resolver returns the same value on every invocation.
         */
        object Arbitrary : Factory {
            override fun createVariablesResolver(params: Params): EngineVariablesResolver =
                object : EngineVariablesResolver {
                    override val variableNames: Set<String> = setOf(params.def.name)
                    private val conv = EngineValueConv(
                        params.schema,
                        params.def.type.asSchemaType(params.schema),
                        null
                    )

                    override suspend fun resolve(
                        ctx: EngineVariablesResolver.ResolveCtx,
                        context: EngineExecutionContext
                    ): Map<String, Any?> {
                        params.rs.maybeDelay(params.cfg[ResolverLatencyMillis])
                        maybeThrowResolverException(params.cfg, VariablesResolverExceptionWeight, params.rs)

                        val irValue = Arb.ir(params.schema, params.def.type.asSchemaType(params.schema), params.cfg)
                            // Using the same seed on every call allows this VariablesResolver to return the same
                            // value on every invocation. This is not ideal, but is a workaround for an engine
                            // issue where VariablesResolvers are invoked multiple times during execution:
                            //   https://app.asana.com/1/150975571430/project/1211295233988904/task/1213752457115685
                            //
                            // When the above ticket is resolved, replace the `next` call below with this commented-out
                            // call. This will allow this VR to return different values on each invocation.
                            // .next(env.rs)
                            .next(RandomSource.seeded(params.rs.seed))

                        if (params.exerciseRequiredSelections && params.requiredSelectionSet != null) {
                            EngineDataExerciser.exercise(ctx.objectData, context, params.requiredSelectionSet)
                        }

                        val coercedValue = conv.invert(irValue)
                        return mapOf(params.def.name to coercedValue)
                    }
                }
        }

        /**
         * A [Factory] wrapper that records every [createVariablesResolver] call via [recorder].
         * Created resolvers are stored by `(coordinate, variableName)` and can be retrieved
         * in tests via [resolver] or [allResolvers].
         */
        class Instrumented(val underlying: Factory = Arbitrary) : Factory {
            val resolvers = mutableMapOf<Pair<TypeOrFieldCoordinate, String>, VariablesResolver.Instrumented>()
            val recorder = CallRecorder.sync { params: Params ->
                val resolver = Instrumented(
                    underlying.createVariablesResolver(params)
                )
                resolvers[params.tfc to params.def.name] = resolver
                resolver
            }

            val allResolvers: List<VariablesResolver.Instrumented> get() =
                resolvers.map { it.value }

            fun resolver(
                tfc: TypeOrFieldCoordinate,
                variableName: String
            ): VariablesResolver.Instrumented = requireNotNull(resolvers[tfc to variableName])

            override fun createVariablesResolver(params: Params): EngineVariablesResolver = recorder(params)
        }
    }

    /**
     * An [EngineVariablesResolver] decorator that delegates all behavior to [underlying]
     * while recording each [resolve] invocation via [recorder].
     * Use in tests to assert that a variables resolver was called and inspect its arguments.
     */
    class Instrumented(val underlying: EngineVariablesResolver) : EngineVariablesResolver by underlying {
        data class Args(val ctx: EngineVariablesResolver.ResolveCtx, val context: EngineExecutionContext)

        val recorder = CallRecorder { args: Args -> underlying.resolve(args.ctx, args.context) }

        override suspend fun resolve(
            ctx: EngineVariablesResolver.ResolveCtx,
            context: EngineExecutionContext
        ): Map<String, Any?> = recorder(Args(ctx, context))
    }
}
