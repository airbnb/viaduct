package viaduct.engine.runtime

data class FieldRewriterMetadata(
    val prefix: String,
    val classPath: String,
    val lateResolvedVariables: Map<String, LateResolvedVariable>
) : FieldMetadata
