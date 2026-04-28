package viaduct.tenant.codegen.ksp

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Intermediate descriptor model emitted by the registry extractor per source file.
 * The assembly step consolidates these into a typed [ExecutionRegistry] instance.
 */
internal sealed interface ResolverParams {
    val implFqn: String
    val typeName: String

    data class Node(
        override val implFqn: String,
        override val typeName: String,
        val resolverBaseClass: String,
        val attribution: String = implFqn.substringAfterLast('.'),
        @get:JsonProperty("isBatching") val isBatching: Boolean,
        @get:JsonProperty("isSelective") val isSelective: Boolean,
    ) : ResolverParams

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Field(
        override val implFqn: String,
        override val typeName: String,
        val fieldName: String,
        val resolverBaseClass: String,
        val attribution: String = implFqn.substringAfterLast('.'),
        @get:JsonProperty("isBatching") val isBatching: Boolean,
        @get:JsonProperty("isSelective") val isSelective: Boolean,
        val objectSelections: SelectionsBlock? = null,
        val querySelections: SelectionsBlock? = null,
    ) : ResolverParams
}

internal data class SelectionsBlock(
    val selections: String,
    val variablesProviders: List<VariableProviderDescriptor> = emptyList(),
)

internal data class VariableProviderDescriptor(
    val kind: String,
    val name: String,
    val path: String?,
    val providedVariables: Map<String, String> = emptyMap(),
)

internal data class ResolverDescriptorFile(
    val nodes: List<ResolverParams.Node>,
    val fields: List<ResolverParams.Field>,
) {
    @JsonIgnore
    fun isEmpty(): Boolean = nodes.isEmpty() && fields.isEmpty()
}
