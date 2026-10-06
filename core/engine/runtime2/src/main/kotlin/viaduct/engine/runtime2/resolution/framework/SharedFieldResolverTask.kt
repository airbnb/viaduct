package viaduct.engine.runtime2.resolution.framework

/**
 * Architectural contract for a running field-resolution task retaining its concrete publication occurrence.
 * A task supplies its context through [publication]; it is not itself a publication occurrence.
 * The concrete publication type preserves access to its operation and occurrence metadata.
 *
 * Resolution is the intended end product. The maintained Resolver01-23 implementations help preserve
 * its architectural integrity by exercising the decomposition and encapsulation of resolver concerns
 * across simpler algorithms and different execution structures. This interface keeps the central
 * field-task role and its typed publication ownership explicit across those implementations.
 *
 * No caller currently consumes this interface polymorphically; enforcing this common structure is intentional.
 * Retain it for that architectural role even without shared execution callers.
 */
internal interface SharedFieldResolverTask<out P : SharedFieldPublicationOccurrence<*, *>> {
    val publication: P
}
