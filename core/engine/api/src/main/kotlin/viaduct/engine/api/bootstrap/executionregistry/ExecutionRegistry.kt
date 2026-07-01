@file:Suppress("MemberVisibilityCanBePrivate")

package viaduct.engine.api.bootstrap.executionregistry

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Data model for module index JSON emitted by build tooling (KSP) and consumed by codegen and the engine at runtime.
 *
 * These files are generated build outputs and packaged as resources:
 *   META-INF/viaduct/modules/<tenantpkg>.json
 *
 * Then consumed by the bootstrapper.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExecutionRegistryConfigFile(
    /** Version of the module-index JSON schema */
    val version: String,
    /** FQN of the ExecutorFactory implementation. */
    val executorFactory: String,
    /** Slash-separated tenant module name associated with this registry file. */
    val tenantName: String? = null,
    val nodes: List<NodeEntryConfig> = emptyList(),
    val fields: List<FieldEntryConfig> = emptyList(),
    /** FQN of the class annotated with @TenantBootstrapper, or null if none was declared. */
    val bootstrapClass: String? = null,
    /** @GraphQLFragment definitions, carried to runtime to resolve spreads in ctx.query/ctx.mutation strings. */
    val namedFragments: List<String> = emptyList(),
)

data class NodeEntryConfig(
    val typeName: String,
    @get:JsonProperty("isBatching") val isBatching: Boolean,
    @get:JsonProperty("isSelective") val isSelective: Boolean,
    /**
     * A string used as a prefix for metrics tags and log-message labels.
     * This string is intended to give developers and operators strong guidance to the specific code implementation of a
     * given executor.
     */
    val attribution: String,
    /**
     * Tenant-API-specific data for this node entry. The engine does not interpret this map;
     * the tenant's ExecutorFactory reads it at bootstrap time.
     */
    val tenantAPIData: Map<String, Any?>,
)

data class FieldEntryConfig(
    val typeName: String,
    val fieldName: String,
    @get:JsonProperty("isBatching") val isBatching: Boolean,
    @get:JsonProperty("isSelective") val isSelective: Boolean,
    /**
     * A string used as a prefix for metrics tags and log-message labels.
     * This string is intended to give developers and operators strong guidance to the specific code implementation of a
     * given executor.
     */
    val attribution: String,
    /**
     * Required object-level selections needed to resolve this field, expressed as a raw fragment string.
     * Optional: many resolvers need no required selections.
     */
    val objectSelections: SelectionsBlockConfig? = null,
    /**
     * Required query-level selections needed to resolve this field (e.g., viewer or other root context).
     * Optional and independent of objectSelections.
     */
    val querySelections: SelectionsBlockConfig? = null,
    /**
     * Tenant-API-specific data for this field entry. The engine does not interpret this map;
     * the tenant's ExecutorFactory reads it at bootstrap time.
     */
    val tenantAPIData: Map<String, Any?>,
)

data class SelectionsBlockConfig(
    /**
     * Raw GraphQL fragment string (e.g., `fragment _ on Type { ... }`).
     * Parsed without schema during executor construction; schema validation happens later as an explicit step.
     */
    val selections: String,
    /**
     * Variables required by `selections` (e.g., for @include/@skip), with tenant-API-specific sourcing rules.
     */
    val variablesProviders: List<VariableProviderEntryConfig> = emptyList(),
)

data class VariableProviderEntryConfig(
    /**
     * Map of variable name -> encoded type expression used by Viaduct (e.g., `Boolean!` encoded as `! Boolean`).
     *
     * Example:
     *   { "includeDetails": "! Boolean" }
     */
    val providedVariables: Map<String, String>,
    /**
     * Tenant-API-specific variable source description.
     *
     * This matches the JSON shape:
     *   "providerVariablesAPIData": { "type": "...", "path": "..." }
     */
    val providerVariablesAPIData: ProviderVariablesAPIData,
)

/**
 * Sealed model for variable providers. The JSON encoding uses a discriminator field:
 *   { "type": "fromArgument" | "fromObjectField", "path": "<...>" }
 */
data class ProviderVariablesAPIData(
    val type: String,
    val path: String
)
