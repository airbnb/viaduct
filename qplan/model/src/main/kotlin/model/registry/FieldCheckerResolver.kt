package model.registry

import model.Arguments
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

/** Executes one field checker from its resolved arguments and named materialized input pairs. */
typealias FieldCheckerFunction =
    suspend (
        Arguments.Resolved,
        Map<String, CheckerInput>,
        ResolutionExecutionContext,
    ) -> CheckerResult

/**
 * A field checker supplied by the reasoning world's external resolver registry.
 *
 * [CheckerResolverBase] owns the named fragment-pair mechanics shared by checker kinds. A field
 * checker adds the checked field target and evaluates its function with that field occurrence's
 * arguments.
 */
class FieldCheckerResolver private constructor(
    target: ResolverTarget.FieldCheckerTarget,
    fragmentTemplates: Map<String, ResolverFragmentTemplates>,
    queryType: ViaductSchema.Object,
    private val function: FieldCheckerFunction,
) : CheckerResolverBase<ResolverTarget.FieldCheckerTarget>(target, fragmentTemplates, queryType) {
    /** Runs each named provider with this field occurrence's arguments. */
    public override suspend fun provideVariables(arguments: Arguments.Resolved): Map<String, model.EngineInputData?> = super.provideVariables(arguments)

    suspend operator fun invoke(
        arguments: Arguments.Resolved,
        inputs: Map<String, CheckerInput>,
        executionContext: ResolutionExecutionContext,
    ): CheckerResult = evaluateRelation(arguments, inputs, executionContext)

    /**
     * Evaluates the deterministic checker relation for a semantic judgment.
     *
     * This is not an observed checker application and establishes no execution-count property.
     */
    suspend fun evaluateRelation(
        arguments: Arguments.Resolved,
        inputs: Map<String, CheckerInput>,
        executionContext: ResolutionExecutionContext,
    ): CheckerResult = function(arguments, inputs, executionContext)

    companion object {
        fun of(
            field: ViaductSchema.ObjectField,
            queryType: ViaductSchema.Object,
            fragmentTemplates: Map<String, ResolverFragmentTemplates> = emptyMap(),
            function: FieldCheckerFunction,
        ): FieldCheckerResolver =
            FieldCheckerResolver(
                target = ResolverTarget.FieldCheckerTarget(field),
                fragmentTemplates = fragmentTemplates,
                queryType = queryType,
                function = function,
            )
    }
}
