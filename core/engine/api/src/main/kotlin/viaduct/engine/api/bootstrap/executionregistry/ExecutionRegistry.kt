@file:Suppress("MemberVisibilityCanBePrivate")

package viaduct.engine.api.bootstrap.executionregistry

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Stable `apiName` wire value for the default tenant API — the one this engine ships and generates for
 * unless a caller says otherwise.
 *
 * Declared here because it is part of [ExecutionRegistryConfigFile]'s wire format and because engine
 * code legitimately needs to recognize its own default API at runtime (see
 * `RemoteResolverSelection`). It is *not* a registry of every tenant API: `apiName` is deliberately an
 * open string so any other tenant API — including ones built outside this engine — declares its own
 * name in its own module. Do not add names here.
 *
 * Producers that cannot reference Kotlin mirror this literal instead: the `api_name` attr default in
 * the Bazel rule, and the Gradle plugin/build-logic constants.
 */
const val KOTLIN_API_NAME = "kotlin"

/**
 * Data model for module index JSON emitted by build tooling (KSP) and consumed by codegen and the engine at runtime.
 *
 * These files are generated build outputs and packaged as resources:
 *   META-INF/viaduct/modules/<tenantpkg>.json
 *
 * Then consumed by the bootstrapper.
 *
 * Identified by the pair `<`[tenantName]`, `[apiName]`>`; [executorFactory] selects how a config is
 * materialized but does not identify it. See [ConfigKey] and
 * `projects/viaduct/oss/impldocs/execution-registry-bootstrap.md`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExecutionRegistryConfigFile(
    /** Version of the module-index JSON schema */
    val version: String,
    /**
     * FQN of the ExecutorFactory implementation used to interpret this config's entries.
     *
     * This is the value side of the configuration map: it determines how [nodes] and [fields] are
     * turned into executors. It is *not* part of the configuration's identity — see [apiName].
     */
    val executorFactory: String,
    /**
     * Slash-separated tenant module name associated with this registry file.
     *
     * The tenant-module half of the `<tenantName, apiName>` configuration key. Nullable only for
     * wire compatibility; [ModuleConfigSource.from] rejects sources that omit it.
     */
    val tenantName: String? = null,
    /**
     * Stable name of the tenant API implementation that produced this config; [KOTLIN_API_NAME] is the
     * engine's default, and every other API declares its own name in its own module.
     *
     * The tenant-API half of the `<tenantName, apiName>` configuration key. This is an open string
     * rather than a closed engine enum so a tenant API built outside this engine can declare its own
     * stable name without an engine change. A tenant may contribute one config per API implementation
     * when those implementations are compatible; such configs differ in this field.
     *
     * Open, but not arbitrary: the name must be a **valid Java identifier** (a Java identifier start
     * character followed by identifier part characters, e.g. `kotlin`, `java`, `builtin_query_node`).
     * The name appears in build-tool arguments, diagnostics, and generated artifacts, so restricting it
     * to identifier syntax keeps it usable as a bare token in all of them and keeps names comparable
     * without normalization rules. Build tooling enforces this when it assembles a config.
     *
     * Nullable only for wire compatibility; [ModuleConfigSource.from] rejects sources that omit it
     * or leave it blank.
     */
    val apiName: String? = null,
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
