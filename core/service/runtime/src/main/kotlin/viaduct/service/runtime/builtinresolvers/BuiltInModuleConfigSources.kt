package viaduct.service.runtime.builtinresolvers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.service.api.spi.InputStreamSource

/** Config-file version emitted for all generated built-in module configs. */
private const val BUILTIN_CONFIG_VERSION = "1"

/** Shared mapper for serializing generated built-in module configs; safe to reuse across factories. */
internal val builtinModuleConfigObjectMapper: ObjectMapper = jacksonObjectMapper()

/**
 * Builds a non-batching, non-selective [FieldEntryConfig] — the shape every built-in field entry
 * uses (built-in resolvers are neither batching nor selective and carry no tenant API data).
 */
internal fun builtinFieldEntry(
    typeName: String,
    fieldName: String,
    attribution: String,
): FieldEntryConfig =
    FieldEntryConfig(
        typeName = typeName,
        fieldName = fieldName,
        isBatching = false,
        isSelective = false,
        attribution = attribution,
        tenantAPIData = emptyMap(),
    )

/**
 * Serializes [fields] into an [ExecutionRegistryConfigFile] for [executorFactoryName] and wraps it
 * in an in-memory [ModuleConfigSource] named after [tenantName], so generated built-ins flow through
 * the same file-based bootstrap path as resource-backed tenant modules.
 */
internal fun buildBuiltinModuleConfigSource(
    tenantName: String,
    executorFactoryName: String,
    fields: List<FieldEntryConfig>,
    objectMapper: ObjectMapper,
): ModuleConfigSource {
    val config = ExecutionRegistryConfigFile(
        version = BUILTIN_CONFIG_VERSION,
        executorFactory = executorFactoryName,
        tenantName = tenantName,
        fields = fields,
    )
    return ModuleConfigSource.from(
        InputStreamSource.fromString(objectMapper.writeValueAsString(config), name = tenantName),
    )
}
