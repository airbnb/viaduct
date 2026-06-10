package viaduct.tenant.codegen.cli

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.ProviderVariablesAPIData
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlockConfig
import viaduct.engine.api.bootstrap.executionregistry.VariableProviderEntryConfig
import viaduct.tenant.codegen.ksp.ResolverDescriptorFile
import viaduct.tenant.codegen.ksp.ResolverParamsJsonCodec

internal object TenantModuleConfigAssembler {
    private const val REGISTRY_VERSION = "1"

    private val mapper: ObjectMapper = jacksonObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    private val codec = ResolverParamsJsonCodec()

    fun writeRegistry(
        descriptorJsons: List<String>,
        executorFactory: String,
        tenantPackage: String,
        outputDir: File,
    ) {
        writeRegistryFromDescriptors(
            descriptors = descriptorJsons.map(codec::decode),
            executorFactory = executorFactory,
            tenantPackage = tenantPackage,
            outputDir = outputDir,
        )
    }

    private fun writeRegistryFromDescriptors(
        descriptors: List<ResolverDescriptorFile>,
        executorFactory: String,
        tenantPackage: String,
        outputDir: File,
    ) {
        val bootstrapClasses = descriptors.mapNotNull { it.bootstrapClass }
        if (bootstrapClasses.size > 1) {
            error(
                "Each tenant module may declare at most one @TenantBootstrapper class, " +
                    "but found ${bootstrapClasses.size}: $bootstrapClasses",
            )
        }

        val outputFile = outputDir
            .resolve(REGISTRY_RESOURCE_PATH)
            .resolve("$tenantPackage.json")

        outputFile.parentFile.mkdirs()

        mapper.writerWithDefaultPrettyPrinter().writeValue(
            outputFile,
            buildExecutionRegistry(
                executorFactory = executorFactory,
                descriptors = descriptors,
                bootstrapClass = bootstrapClasses.singleOrNull(),
            ),
        )
    }

    private fun buildExecutionRegistry(
        executorFactory: String,
        descriptors: List<ResolverDescriptorFile>,
        bootstrapClass: String?,
    ): ExecutionRegistryConfigFile {
        val nodes = descriptors.flatMap { it.nodes }.map { node ->
            NodeEntryConfig(
                typeName = node.typeName,
                isBatching = node.isBatching,
                isSelective = node.isSelective,
                attribution = node.attribution,
                tenantAPIData = mapOf(
                    "resolverClass" to node.implFqn,
                    "resolverBaseClass" to node.resolverBaseClass,
                ),
            )
        }

        val fields = descriptors.flatMap { it.fields }.map { field ->
            FieldEntryConfig(
                typeName = field.typeName,
                fieldName = field.fieldName,
                isBatching = field.isBatching,
                isSelective = field.isSelective,
                attribution = field.attribution,
                objectSelections = field.objectSelections?.toEngineSelectionsBlock(),
                querySelections = field.querySelections?.toEngineSelectionsBlock(),
                tenantAPIData = mapOf(
                    "resolverClass" to field.implFqn,
                    "resolverBaseClass" to field.resolverBaseClass,
                    "returnTypeName" to field.returnTypeName,
                    "hasArguments" to field.hasArguments,
                    "queryTypeName" to field.queryTypeName,
                ),
            )
        }

        return ExecutionRegistryConfigFile(
            version = REGISTRY_VERSION,
            executorFactory = executorFactory,
            nodes = nodes,
            fields = fields,
            bootstrapClass = bootstrapClass,
        )
    }

    private fun viaduct.tenant.codegen.ksp.SelectionsBlock.toEngineSelectionsBlock(): SelectionsBlockConfig {
        return SelectionsBlockConfig(
            selections = selections,
            variablesProviders = variablesProviders.map { provider ->
                VariableProviderEntryConfig(
                    providedVariables = provider.providedVariables,
                    providerVariablesAPIData = ProviderVariablesAPIData(
                        type = provider.kind,
                        path = requireNotNull(provider.path) {
                            "VariableProviderDescriptor.path must not be null for variable '${provider.name}'"
                        },
                    ),
                )
            },
        )
    }
}
