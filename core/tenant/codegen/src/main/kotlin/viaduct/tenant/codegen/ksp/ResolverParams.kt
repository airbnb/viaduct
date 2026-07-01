package viaduct.tenant.codegen.ksp

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Intermediate descriptor model emitted by the registry extractor per source file.
 * The assembly step consolidates these into a typed [ExecutionRegistry] instance.
 *
 * This model is the single source of truth for the per-file descriptor JSON shape. It is
 * shared by both the Kotlin KSP extractor (in this module) and the Java annotation-processor
 * extractor (`:x:javaapi:codegen-apt`), so a change here is a compile error in both producers
 * rather than a silent runtime mismatch.
 */
sealed interface ResolverParams {
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
        @get:JsonProperty("hasArguments") val hasArguments: Boolean = false,
        val queryTypeName: String = "Query",
        val returnTypeName: String? = null,
    ) : ResolverParams
}

data class SelectionsBlock(
    val selections: String,
    val variablesProviders: List<VariableProviderDescriptor> = emptyList(),
)

data class VariableProviderDescriptor(
    val kind: String,
    val name: String,
    val path: String?,
    val providedVariables: Map<String, String> = emptyMap(),
)

/** The kind of operation declared by a [@GraphQLOperation][viaduct.api.documents.GraphQLOperation]. */
enum class OperationKind {
    QUERY,
    MUTATION,
}

/** An operation extracted from a @GraphQLOperation object. [kind] reflects its base class. */
data class OperationDescriptor(
    val text: String,
    val kind: OperationKind,
    /** FQN of the @GraphQLOperation object, used in assembly-time error messages. */
    val implFqn: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PerSourceDescriptorFile(
    val nodes: List<ResolverParams.Node> = emptyList(),
    val fields: List<ResolverParams.Field> = emptyList(),
    val grtPackagePrefix: String? = null,
    val bootstrapClass: String? = null,
    /** Fragment definition strings extracted from @GraphQLFragment objects in this source file. */
    val namedFragments: List<String> = emptyList(),
    /**
     * Operations extracted from @GraphQLOperation objects in this source file. We extract these so
     * we can validate them against the collected named fragments; they do not need to go into the
     * final registry.
     */
    val namedOperations: List<OperationDescriptor> = emptyList(),
) {
    @JsonIgnore
    fun isEmpty(): Boolean = nodes.isEmpty() && fields.isEmpty() && bootstrapClass == null && namedFragments.isEmpty() && namedOperations.isEmpty()
}
