package semantics.correctresolution

import model.Assumptions
import model.Fragment
import model.ObjectEngineResult
import model.merge
import model.requireQueryTypeDef
import semantics.shared.SharedOperationContext

internal fun ObjectEngineResult.correctResolution(
    operation: SharedOperationContext<*>,
    fragment: Fragment,
): Boolean =
    fragment.nominalType == operation.world.schema.requireQueryTypeDef() &&
        correctResolution(
            operation,
            fragment.subselections
                .merge(operation.world.schema.requireQueryTypeDef()),
        )

internal fun ObjectEngineResult.rootedAndWellTyped(
    world: Assumptions,
    fragment: Fragment
): Boolean =
    fragment.nominalType == world.schema.requireQueryTypeDef() &&
        this.rootedAndWellTyped(world)

internal fun ObjectEngineResult.conformsToFragment(
    operation: SharedOperationContext<*>,
    fragment: Fragment
): Boolean = conformsToSelections(operation, fragment.subselections)
