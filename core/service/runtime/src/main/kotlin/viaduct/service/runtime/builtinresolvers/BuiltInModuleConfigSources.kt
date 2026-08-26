package viaduct.service.runtime.builtinresolvers

import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigFactory
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.service.api.spi.InputStreamSource

/** Config-file version emitted for all generated built-in module configs. */
private const val BUILTIN_CONFIG_VERSION = "1"

/**
 * Generates the built-in module config sources for [schema].
 *
 * Runs the [ModuleConfigFactory]s for the built-in resolvers and returns the config sources they
 * contribute (factories return `null` when their fields are absent from [schema], and those are
 * dropped).
 *
 * When [defaultQueryNodeResolversEnabled] is false, no built-ins are contributed at all: both the
 * `Query.node`/`Query.nodes` resolvers and the `@namespaceType` resolvers are gated behind this
 * single flag.
 */
fun builtinModuleConfigSources(
    schema: ViaductSchema,
    defaultQueryNodeResolversEnabled: Boolean,
): List<ModuleConfigSource> {
    if (!defaultQueryNodeResolversEnabled) return emptyList()
    return listOf(
        QueryNodeModuleConfigFactory(schema),
        NamespaceTypeModuleConfigFactory(schema),
    ).mapNotNull(ModuleConfigFactory::moduleConfigSource)
}

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
 *
 * [apiName] is the tenant-API half of the config key. Built-ins pass an explicit stable name for their
 * producer rather than deriving one from [executorFactoryName] — a synthetic built-in factory is not
 * itself a tenant API identity.
 */
internal fun buildBuiltinModuleConfigSource(
    tenantName: String,
    apiName: String,
    executorFactoryName: String,
    fields: List<FieldEntryConfig>,
): ModuleConfigSource {
    val config = ExecutionRegistryConfigFile(
        version = BUILTIN_CONFIG_VERSION,
        executorFactory = executorFactoryName,
        tenantName = tenantName,
        apiName = apiName,
        fields = fields,
    )
    return ModuleConfigSource.from(
        InputStreamSource.fromString(ExecutionRegistryConfigFile.toJson(config), name = tenantName),
    )
}
