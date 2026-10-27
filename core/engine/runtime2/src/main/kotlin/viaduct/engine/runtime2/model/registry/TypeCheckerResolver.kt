package viaduct.engine.runtime2.model.registry

import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.argumentsOfGround
import viaduct.graphql.schema.ViaductSchema

/** Executes one type checker from its named materialized input pairs. */
typealias TypeCheckerFunction =
    suspend (
        Map<String, CheckerInput>,
        ResolutionExecutionContext,
    ) -> CheckerResult

/**
 * A type checker supplied by the reasoning world's external resolver registry.
 *
 * A type-checker occurrence is owned by its checked concrete object type and has no field argument
 * tuple. Its named fragment pairs may therefore define from-field and provider variables, but not
 * from-argument variables.
 */
class TypeCheckerResolver private constructor(
    target: ResolverTarget.TypeCheckerTarget,
    fragmentTemplates: Map<String, ResolverFragmentTemplates>,
    queryType: ViaductSchema.Object,
    private val applicability: (ObjectEngineResult, List<PathComponent>) -> Boolean,
    private val function: TypeCheckerFunction,
) : CheckerResolverBase<ResolverTarget.TypeCheckerTarget>(target, fragmentTemplates, queryType) {
    /** A pure, total metadata predicate: never read result data or initiate execution here. */
    fun isApplicable(
        root: ObjectEngineResult,
        path: List<PathComponent>
    ): Boolean = applicability(root, path)

    /** Runs each named provider with the type checker's empty argument tuple. */
    suspend fun provideVariables(): Map<String, viaduct.engine.runtime2.model.EngineInputData?> = super.provideVariables(argumentsOfGround(emptyMap()))

    suspend operator fun invoke(
        inputs: Map<String, CheckerInput>,
        executionContext: ResolutionExecutionContext,
    ): CheckerResult = evaluateRelation(inputs, executionContext)

    /**
     * Evaluates the deterministic checker relation for a semantic judgment.
     *
     * This is not an observed checker application and establishes no execution-count property.
     */
    suspend fun evaluateRelation(
        inputs: Map<String, CheckerInput>,
        executionContext: ResolutionExecutionContext,
    ): CheckerResult = function(inputs, executionContext)

    companion object {
        fun of(
            type: ViaductSchema.Object,
            queryType: ViaductSchema.Object,
            fragmentTemplates: Map<String, ResolverFragmentTemplates> = emptyMap(),
            /** Airbnb-only applicability hook; OSS callers should keep the default. */
            isApplicable: (ObjectEngineResult, List<PathComponent>) -> Boolean = { _, _ -> true },
            function: TypeCheckerFunction,
        ): TypeCheckerResolver =
            TypeCheckerResolver(
                target = ResolverTarget.TypeCheckerTarget(type),
                fragmentTemplates = fragmentTemplates,
                queryType = queryType,
                applicability = isApplicable,
                function = function,
            )
    }
}
