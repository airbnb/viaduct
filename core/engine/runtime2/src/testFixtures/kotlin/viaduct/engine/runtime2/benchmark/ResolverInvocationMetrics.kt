package viaduct.engine.runtime2.benchmark

import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.usedVariables
import viaduct.engine.runtime2.model.variableArgumentNames
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation

/**
 * Variable-bearing arguments retained in the recorded key. Resolution preserves symbolic
 * keys here; families that record grounded keys report zero.
 */
val ResolverInvocationObservation.variableArgumentCount: Int
    get() = (occurrencePath.last() as ObjectEngineResult.ObjectKey).arguments.variableArgumentNames().size

/** Owning occurrences of variables used by the invocation's original argument expressions. */
val ResolverInvocationObservation.variableResolverOccurrenceIds: Set<ResolverOccurrenceId>
    get() = (occurrencePath.last() as ObjectEngineResult.ObjectKey).arguments.usedVariables()
        .mapNotNullTo(linkedSetOf()) { it.instanceId?.resolverOccurrenceId }
