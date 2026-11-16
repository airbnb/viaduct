package viaduct.engine.runtime2.model.registry

import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.mapSelectionVariables
import viaduct.engine.runtime2.model.mapVariableTemplates

internal fun Fragment.mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): Fragment =
    Fragment.of(
        nominalType = nominalType,
        subselections = subselections.mapSelectionVariables(transform),
    )

internal fun List<ObjectEngineResult.Key>.mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): List<ObjectEngineResult.Key> =
    map { key ->
        ObjectEngineResult.Key.of(
            field = key.field,
            arguments = key.arguments.mapVariableTemplates(key.field, transform),
        )
    }
