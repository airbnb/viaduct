@file:Suppress("MemberVisibilityCanBePrivate")

package viaduct.engine.api.bootstrap.executionregistry

/**
 * Data model for module index JSON emitted by build tooling (KSP) and consumed by codegen and the engine at runtime.
 *
 * These files are generated build outputs and packaged as resources:
 *   META-INF/viaduct/modules/<tenantpkg>.json
 *
 * Then consumed by the bootstrapper.
 */
data class ExecutionRegistryConfigFile(
    /** Version of the module-index JSON schema */
    val version: String,
    /** FQN of the ExecutorFactory implementation. */
    val executorFactory: String,
    /** Package where GRT classes live for this module (e.g. "viaduct.api.grts"). Extracted by KSP. */
    val grtPackagePrefix: String,
    val nodes: List<NodeEntryConfig> = emptyList(),
    val fields: List<FieldEntryConfig> = emptyList(),
    /** FQN of the class annotated with @TenantBootstrapper, or null if none was declared. */
    val bootstrapClass: String? = null,
)

data class NodeEntryConfig(
    val typeName: String,
    val isBatching: Boolean,
    val isSelective: Boolean,
    /**
     * A string used as a prefix for metrics tags and log-message labels.
     * This string is intended to give developers and operators strong guidance to the specific code implementation of a
     * given executor.
     */
    val attribution: String,
    val tenantAPIData: NodeAPIData,
)

data class NodeAPIData(
    /** Resolver implementation class name (FQN that runtime used to discover via scanning). */
    val resolverClass: String,
    /** Generated resolver base class name, used as a stable join key with codegen output. */
    val resolverBaseClass: String,
)

data class FieldEntryConfig(
    val typeName: String,
    val fieldName: String,
    val isBatching: Boolean,
    val isSelective: Boolean,
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
    val tenantAPIData: FieldAPIData,
)

data class FieldAPIData(
    /** Resolver implementation class name (FQN that runtime used to discover via scanning). */
    val resolverClass: String,
    /** Generated resolver base class name, used as a stable join key with codegen output. */
    val resolverBaseClass: String,
    /** Simple GraphQL type name of the field's return type (e.g. "TestNode"). Used to resolve the result GRT class at bootstrap time. Null for scalar/enum return types. */
    val returnTypeName: String? = null,
    /** True if the resolver's Context declares a non-[Arguments.NoArguments] type parameter. Used to resolve the generated Arguments GRT class at bootstrap time. */
    val hasArguments: Boolean = false,
    /** Name of the root query type (e.g. "Query"). Used to resolve the generated Query GRT class at bootstrap time without needing the runtime schema. */
    val queryTypeName: String,
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
