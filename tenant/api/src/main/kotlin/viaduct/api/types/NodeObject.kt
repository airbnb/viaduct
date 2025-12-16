package viaduct.api.types

import viaduct.apiannotations.StableApi

/**
 * Tagging interface for types that implement the Node interface.
 * This includes both Node interfaces and Node object implementations.
 */
@StableApi
interface NodeCompositeOutput : CompositeOutput

/**
 * Tagging interface for object types that implement the Node interface
 */
@StableApi
interface NodeObject : Object, NodeCompositeOutput
