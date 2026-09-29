package model.registry

import model.argumentsOfGround
import viaduct.engine.api.CheckerResult
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
    private val function: TypeCheckerFunction,
) : CheckerResolverBase<ResolverTarget.TypeCheckerTarget>(target, fragmentTemplates, queryType) {
    /** Runs each named provider with the type checker's empty argument tuple. */
    suspend fun provideVariables(): Map<String, model.EngineInputData?> = super.provideVariables(argumentsOfGround(emptyMap()))

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
            function: TypeCheckerFunction,
        ): TypeCheckerResolver =
            TypeCheckerResolver(
                target = ResolverTarget.TypeCheckerTarget(type),
                fragmentTemplates = fragmentTemplates,
                queryType = queryType,
                function = function,
            )
    }
}
