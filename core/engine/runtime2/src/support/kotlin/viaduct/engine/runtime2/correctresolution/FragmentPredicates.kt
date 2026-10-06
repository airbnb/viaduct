package viaduct.engine.runtime2.correctresolution

import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

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
