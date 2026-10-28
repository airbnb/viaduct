package viaduct.engine.runtime2.resolvers

import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.registry.FieldValueResolver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/** Returns this resolver's object fragment grounded at exact occurrence [path]. */
fun FieldValueResolver.objectFragmentAt(
    operation: SharedOperationContext<*>,
    root: ObjectEngineResult,
    path: List<PathComponent>,
): ObjectSelectionForest =
    instantiateFragmentsAt(root, path)
        .objectFragment
        .constructionSelections.applicableGroundSelections(operation, target.field.containingDef)
