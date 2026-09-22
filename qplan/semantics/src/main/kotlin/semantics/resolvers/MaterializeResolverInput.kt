package semantics.resolvers

import model.MaterializeSelectionForest
import model.ObjectEngineResult
import semantics.shared.CycleCheckState
import semantics.shared.CycleTask
import semantics.shared.SharedOperationContext
import semantics.shared.materializeResult
import viaduct.engine.api.EngineObjectData

/**
 * Materializes a Resolver01-23 runtime object or declared Query-fragment input.
 *
 * Delegates to [materializeResult]: selected cells and value promises must be installed, while
 * values and selection bindings may still be pending. The caller's [operation] and [cycleChecker]
 * are passed through unchanged. Claimed field- and type-checker slots are awaited, combined, and
 * enforced before the raw value is awaited; unclaimed slots default open, and an applicable denial
 * short-circuits the raw read.
 *
 * Resolver26 uses its distinct runtime input materializer to reserve symbolic cells and value
 * promises before their producers install them. Correctness replay, including replay of
 * Resolver26 inputs, and nested `ctx.query` results use [materializeResult] directly.
 */
internal suspend fun ObjectEngineResult.materializeResolverInput(
    operation: SharedOperationContext<*>,
    cycleChecker: CycleCheckState,
    selections: MaterializeSelectionForest,
    reader: CycleTask,
): EngineObjectData.Sync =
    materializeResult(operation, selections, reader, cycleChecker)
