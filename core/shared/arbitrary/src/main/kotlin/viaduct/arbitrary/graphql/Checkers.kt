package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.CheckerExecutor.Instrumented
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.CheckerExecutor as EngineCheckerExecutor

/**
 * Generate a [CheckerExecutor] for fields and types in the provided schema.
 *
 * This generator is suitable for generating single-shot [CheckerExecutor]s, though it cannot
 * guarantee that a generated CheckerExecutor won't form illegal RSS cycles with other RSSes in a system.
 * If cycle-free generation is required, see [Arb.Companion.viaduct]
 *
 * This generator knows how to interpret these config keys:
 * - [FieldCheckerWeight]
 * - [TypeCheckerWeight]
 * - [CheckerExecutorFactory]
 * - [ExerciseRequiredSelectionsWeight]
 * - [RequiredSelectionSetWeight]
 * - [CheckerErrorWeight]
 * - [CheckerExceptionWeight]
 */
fun Arb.Companion.checkerExecutor(
    schema: ViaductSchema,
    tfc: TypeOrFieldCoordinate,
    cfg: Config = Config.default
): Arb<EngineCheckerExecutor> =
    arbitrary { rs ->
        val env = ViaductGenEnv(schema, cfg, rs)
        env.checkerExecutorGen.gen(tfc)
    }

fun interface CheckerExecutorGen {
    fun gen(key: TypeOrFieldCoordinate): EngineCheckerExecutor

    companion object {
        internal operator fun invoke(env: ViaductGenEnv): CheckerExecutorGen = CheckerExecutorGenImpl(env)
    }
}

private class CheckerExecutorGenImpl(private val env: ViaductGenEnv) : CheckerExecutorGen {
    override fun gen(tfc: TypeOrFieldCoordinate): EngineCheckerExecutor {
        val factory = env.cfg[CheckerExecutorFactory]

        val requiredSelectionSets =
            List(env.rs.count(env.cfg[RequiredSelectionSetWeight])) {
                val typeCondition = Arb.of(setOf(env.schemas.schema.queryType.name, tfc.first))
                    .next(env.rs)
                env.requiredSelectionSetGen.gen(tfc, typeCondition, forChecker = true, 0)
            }
                .filterNotNull()
                .mapIndexed { index, rss -> index.toString() to rss }
                .toMap()

        return factory.createCheckerExecutor(
            CheckerExecutor.Params(
                tfc,
                requiredSelectionSets,
                env.rs.sampleWeight(env.cfg[ExerciseRequiredSelectionsWeight]),
                env.cfg,
                env.rs.fork()
            )
        )
    }
}

object CheckerExecutor {
    data class Params(
        val key: TypeOrFieldCoordinate,
        val requiredSelectionSets: Map<String, RequiredSelectionSet>,
        val exerciseRequiredSelections: Boolean,
        val cfg: Config,
        val rs: RandomSource
    )

    fun interface Factory {
        fun createCheckerExecutor(params: Params): EngineCheckerExecutor

        /**
         * A [Factory] with arbitrary behavior.
         *
         * This factory knows how to handle these config keys:
         * - [CheckerErrorWeight]
         * - [CheckerExceptionWeight]
         */
        object Arbitrary : Factory {
            override fun createCheckerExecutor(params: Params): EngineCheckerExecutor = Impl(params)

            private class Impl(private val params: Params) : EngineCheckerExecutor {
                override val requiredSelectionSets = params.requiredSelectionSets

                override suspend fun execute(
                    arguments: Map<String, Any?>,
                    objectDataMap: Map<String, EngineObjectData>,
                    context: EngineExecutionContext,
                    checkerType: EngineCheckerExecutor.CheckerType
                ): CheckerResult {
                    if (params.exerciseRequiredSelections) {
                        for ((name, rss) in params.requiredSelectionSets) {
                            val eod = requireNotNull(objectDataMap[name])
                            EngineDataExerciser.exercise(eod, context, rss)
                        }
                    }

                    maybeThrowResolverException(params.cfg, CheckerExceptionWeight, params.rs)
                    params.rs.maybeDelay(params.cfg[ResolverLatencyMillis])

                    return if (params.rs.sampleWeight(params.cfg[CheckerErrorWeight])) {
                        mkError()
                    } else {
                        CheckerResult.Success
                    }
                }

                private fun mkError(): ErrorImpl = ErrorImpl(Arb.boolean().next(params.rs))
            }

            private class ErrorImpl(val isErrorForResolver: Boolean) : CheckerResult.Error {
                override val error: Exception = RuntimeException("Synthetic Checker error, configured by CheckerErrorWeight")

                override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = isErrorForResolver

                override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
            }
        }

        class Instrumented(val underlying: Factory = Arbitrary) : Factory {
            val checkers = mutableMapOf<TypeOrFieldCoordinate, CheckerExecutor.Instrumented>()

            val recorder = CallRecorder.sync { params: Params ->
                val result = Instrumented(underlying.createCheckerExecutor(params))
                checkers[params.key] = result
                result
            }

            fun typeChecker(typeName: String): CheckerExecutor.Instrumented = requireNotNull(checkers[typeName to null])

            fun fieldChecker(coord: Coordinate): CheckerExecutor.Instrumented = requireNotNull(checkers[coord])

            override fun createCheckerExecutor(params: Params): EngineCheckerExecutor = recorder(params)
        }
    }

    /**
     * A [EngineCheckerExecutor] that records and times all of its invocations
     * @see CallRecorder
     */
    class Instrumented(val underlying: EngineCheckerExecutor) : EngineCheckerExecutor {
        override val requiredSelectionSets get() = underlying.requiredSelectionSets

        data class Args(
            val arguments: Map<String, Any?>,
            val objectDataMap: Map<String, EngineObjectData>,
            val context: EngineExecutionContext,
            val checkerType: EngineCheckerExecutor.CheckerType
        )

        val recorder = CallRecorder { args: Args ->
            underlying.execute(
                args.arguments,
                args.objectDataMap,
                args.context,
                args.checkerType
            )
        }

        override suspend fun execute(
            arguments: Map<String, Any?>,
            objectDataMap: Map<String, EngineObjectData>,
            context: EngineExecutionContext,
            checkerType: EngineCheckerExecutor.CheckerType
        ): CheckerResult =
            recorder(
                Args(arguments, objectDataMap, context, checkerType)
            )
    }
}
