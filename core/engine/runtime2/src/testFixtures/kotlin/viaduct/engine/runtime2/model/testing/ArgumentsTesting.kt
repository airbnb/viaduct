package viaduct.engine.runtime2.model.testing

import viaduct.engine.runtime2.model.ArgumentResolutionError
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.MaterializeSelection
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.fieldExpressions

/** Replaces selected argument expressions with an error during fixture composition. */
fun Selection.withErrorArguments(argumentNames: Set<String>): Selection =
    Selection.of(
        key =
            ObjectEngineResult.Key.of(
                field = key.field,
                arguments =
                    Arguments.of(
                        key.field,
                        key.arguments
                            .fieldExpressions()
                            .mapValues { (name, value) ->
                                if (name in argumentNames) ArgumentResolutionError else value
                            },
                    ),
            ),
        possibleTypes = possibleTypes,
        inclusionCondition = inclusionCondition,
        subselections = subselections,
    )

/** Replaces selected argument expressions with an error during fixture composition. */
fun MaterializeSelection.withErrorArguments(argumentNames: Set<String>): MaterializeSelection =
    MaterializeSelection.of(
        responseKey = responseKey,
        key =
            ObjectEngineResult.Key.of(
                field = key.field,
                arguments =
                    Arguments.of(
                        key.field,
                        key.arguments
                            .fieldExpressions()
                            .mapValues { (name, value) ->
                                if (name in argumentNames) ArgumentResolutionError else value
                            },
                    ),
            ),
        possibleTypes = possibleTypes,
        inclusionCondition = inclusionCondition,
        subselections = subselections,
    )
