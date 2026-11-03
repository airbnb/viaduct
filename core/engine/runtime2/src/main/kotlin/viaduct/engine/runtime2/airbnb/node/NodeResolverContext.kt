package viaduct.engine.runtime2.airbnb.node

import viaduct.engine.runtime2.model.NodeReferenceIdentity

/** Reserved for Airbnb internal API compatibility; unrelated code must not create or consume this carrier. */
@Deprecated("Airbnb use only")
class NodeResolverContext(
    val retainedContext: Any?,
    val identity: NodeReferenceIdentity? = null,
)
