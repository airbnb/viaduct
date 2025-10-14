package viaduct.engine.runtime.tenantloading

import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.Coordinate
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.validation.Validator

/** A concrete implementation of a [Validator] for [DispatcherRegistry] */
class ExecutorValidator(
    val nodeResolverValidator: Validator<NodeResolverExecutorValidationCtx>,
    val fieldResolverExecutorValidator: Validator<FieldResolverExecutorValidationCtx>,
    val requiredSelectionsValidator: Validator<RequiredSelectionsValidationCtx>,
    val checkerExecutorValidator: Validator<CheckerExecutorValidationCtx>,
) : Validator<ExecutorValidatorContext> {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: ExecutorValidatorContext) {
        ctx.nodeResolverExecutors.forEach { (typeName, executor) ->
            nodeResolverValidator.validate(
                NodeResolverExecutorValidationCtx(typeName, executor)
            )
        }

        val rssValidatedCoords = mutableSetOf<Coordinate>()
        ctx.fieldCheckerExecutors.forEach { (coord, executor) ->
            checkerExecutorValidator.validate(
                CheckerExecutorValidationCtx(coord.first, executor)
            )
            if (executor.requiredSelectionSets.any { it.value != null } &&
                rssValidatedCoords.add(coord)
            ) {
                requiredSelectionsValidator.validate(
                    RequiredSelectionsValidationCtx(
                        coord.first,
                        coord.second,
                        ctx.requiredSelectionSetRegistry
                    )
                )
            }
        }

        ctx.typeCheckerExecutors.forEach { (typeName, executor) ->
            checkerExecutorValidator.validate(
                CheckerExecutorValidationCtx(typeName, executor)
            )
            if (executor.requiredSelectionSets.any { it.value != null }) {
                requiredSelectionsValidator.validate(
                    RequiredSelectionsValidationCtx(
                        typeName,
                        null,
                        ctx.requiredSelectionSetRegistry
                    )
                )
            }
        }

        ctx.fieldResolverExecutors.forEach { (coord, executor) ->
            fieldResolverExecutorValidator.validate(
                FieldResolverExecutorValidationCtx(coord, executor)
            )
            if (executor.hasRequiredSelectionSets() &&
                rssValidatedCoords.add(coord)
            ) {
                requiredSelectionsValidator.validate(
                    RequiredSelectionsValidationCtx(
                        coord.first,
                        coord.second,
                        ctx.requiredSelectionSetRegistry
                    )
                )
            }
        }
    }
}

data class ExecutorValidatorContext(
    val fieldResolverExecutors: Map<Coordinate, FieldResolverExecutor>,
    val nodeResolverExecutors: Map<String, NodeResolverExecutor>,
    val fieldCheckerExecutors: Map<Coordinate, CheckerExecutor>,
    val typeCheckerExecutors: Map<String, CheckerExecutor>,
    val requiredSelectionSetRegistry: RequiredSelectionSetRegistry
)

data class NodeResolverExecutorValidationCtx(
    val typeName: String,
    val executor: NodeResolverExecutor,
)

data class FieldResolverExecutorValidationCtx(
    val coord: Coordinate,
    val executor: FieldResolverExecutor,
)

data class RequiredSelectionsValidationCtx(
    val typeName: String,
    val fieldName: String?,
    val requiredSelectionSetRegistry: RequiredSelectionSetRegistry
)

data class CheckerExecutorValidationCtx(
    val typeName: String,
    val executor: CheckerExecutor
)

/**
 * Represents either a field coordinate, or an object type if the 2nd element is null
 */
typealias TypeOrFieldCoordinate = Pair<String, String?>
