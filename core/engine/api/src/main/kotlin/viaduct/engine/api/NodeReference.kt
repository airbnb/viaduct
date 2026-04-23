package viaduct.engine.api

/**
 * A reference to a node object. Unlike [EngineObjectData], this does not provide access to the
 * node's fields, only its ID.
 */
interface NodeReference : EngineObject {
    /** a serialized representation of a Node's GlobalID */
    val id: String
}
