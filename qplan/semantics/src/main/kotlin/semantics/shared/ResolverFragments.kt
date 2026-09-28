package semantics.shared

import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.registry.FieldValueResolver

/** Returns this resolver's object fragment grounded at exact occurrence [path]. */
fun FieldValueResolver.objectFragmentAt(
    operation: SharedOperationContext<*>,
    root: ObjectEngineResult,
    path: List<PathComponent>,
): ObjectSelectionForest =
    instantiateFragmentsAt(root, path)
        .objectFragment
        .constructionSelections.applicableGroundSelections(operation, target.field.containingDef)
