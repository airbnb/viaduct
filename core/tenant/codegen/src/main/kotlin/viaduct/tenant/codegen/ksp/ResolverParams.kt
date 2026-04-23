package viaduct.tenant.codegen.ksp

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Intermediate descriptor model emitted by the registry extractor.
 *
 * This model is intentionally file-scoped: the extractor produces one
 * [ResolverDescriptorFile] per source file, and the assembly step later
 * consolidates those descriptors into the final tenant config artifact.
 */
internal sealed interface ResolverParams {
    /**
     * Fully-qualified name of the concrete resolver implementation class.
     */
    val implFqn: String

    /**
     * GraphQL type name this resolver is associated with.
     */
    val typeName: String

    /**
     * Node resolver descriptor emitted during the current extractor pass.
     *
     * For the current phase, node descriptors only carry the Kotlin-inherent
     * facts needed to join against schema data in the aggregation step.
     */
    data class Node(
        override val implFqn: String,
        override val typeName: String,
        val resolverBaseClass: String,
        val attribution: String = implFqn.substringAfterLast('.'),
        @get:JsonProperty("isBatching") val isBatching: Boolean,
        @get:JsonProperty("isSelective") val isSelective: Boolean,
    ) : ResolverParams

    /**
     * Field resolver descriptor.
     *
     * This type remains part of the intermediate descriptor model, even though
     * the current extractor pass is intentionally scoped to node resolvers.
     */
    data class Field(
        override val implFqn: String,
        override val typeName: String,
        val fieldName: String,
        val objectValueFragment: String?,
        val queryValueFragment: String?,
        val variableProviders: List<VariableProviderDescriptor>,
    ) : ResolverParams
}

/**
 * Describes one variable provider referenced by a field resolver.
 */
internal data class VariableProviderDescriptor(
    val kind: String,
    val name: String,
    val path: String?,
)

/**
 * File-scoped descriptor written by the extractor.
 *
 * The assembly step consumes many of these and produces the final tenant-level
 * registry/config artifact.
 */
internal data class ResolverDescriptorFile(
    val nodes: List<ResolverParams.Node>,
    val fields: List<ResolverParams.Field>,
) {
    @JsonIgnore
    fun isEmpty(): Boolean = nodes.isEmpty() && fields.isEmpty()
}
