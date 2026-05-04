package viaduct.tenant.codegen.cli

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistry
import viaduct.engine.api.bootstrap.executionregistry.FieldAPIData
import viaduct.engine.api.bootstrap.executionregistry.FieldEntry
import viaduct.engine.api.bootstrap.executionregistry.NodeAPIData
import viaduct.engine.api.bootstrap.executionregistry.NodeEntry
import viaduct.engine.api.bootstrap.executionregistry.ProviderVariablesAPIData
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlock
import viaduct.engine.api.bootstrap.executionregistry.VariableProviderEntry
import viaduct.tenant.codegen.ksp.ResolverDescriptorFile
import viaduct.tenant.codegen.ksp.ResolverParamsJsonCodec

/**
 * Aggregation CLI that combines per-file KSP descriptors into a single tenant module
 * config file at `META-INF/viaduct/modules/<tenantpkg>.json`.
 *
 * Deserializes each per-file [ResolverDescriptorFile], maps them to a typed [ExecutionRegistry],
 * then serializes that — so the JSON shape is always governed by the real data model and a
 * schema change in [ExecutionRegistry] becomes a build error here, not a runtime surprise.
 *
 * Invoked via process-isolated Gradle worker from the Gradle assembly task.
 */
class AssembleTenantModuleConfigFile : CliktCommand(
    name = "assemble-tenant-module-config-file",
) {
    private val descriptorDir: File by option("--descriptor-dir")
        .file(mustExist = true, canBeFile = false)
        .required()

    private val tenantPackage: String by option("--tenant-package")
        .required()

    private val executorFactory: String by option("--executor-factory")
        .default("")

    private val outputDir: File by option("--output-dir")
        .file(mustExist = false, canBeFile = false)
        .required()

    override fun run() {
        descriptorDir.walkTopDown()
            .filter { it.isFile && it.extension != "json" }
            .forEach { echo("WARNING: unexpected file in descriptor dir (not a .json): ${it.name}", err = true) }

        val descriptorFiles = descriptorDir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.relativeTo(descriptorDir).path.replace(File.separatorChar, '/') }
            .toList()

        val codec = ResolverParamsJsonCodec()
        val descriptors: List<ResolverDescriptorFile> = descriptorFiles.map { codec.decode(it.readText()) }

        val registry = buildExecutionRegistry(
            executorFactory = executorFactory,
            descriptors = descriptors,
        )

        val outputFile = outputDir
            .resolve(REGISTRY_RESOURCE_PATH)
            .resolve("$tenantPackage.json")

        outputFile.parentFile.mkdirs()

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputFile, registry)
    }

    private fun buildExecutionRegistry(
        executorFactory: String,
        descriptors: List<ResolverDescriptorFile>,
    ): ExecutionRegistry {
        val nodes = descriptors.flatMap { it.nodes }.map { node ->
            NodeEntry(
                typeName = node.typeName,
                isBatching = node.isBatching,
                isSelective = node.isSelective,
                attribution = node.attribution,
                tenantAPIData = NodeAPIData(
                    resolverClass = node.implFqn,
                    resolverBaseClass = node.resolverBaseClass,
                ),
            )
        }

        val fields = descriptors.flatMap { it.fields }.map { field ->
            FieldEntry(
                typeName = field.typeName,
                fieldName = field.fieldName,
                isBatching = field.isBatching,
                isSelective = field.isSelective,
                attribution = field.attribution,
                objectSelections = field.objectSelections?.toEngineSelectionsBlock(),
                querySelections = field.querySelections?.toEngineSelectionsBlock(),
                tenantAPIData = FieldAPIData(
                    resolverClass = field.implFqn,
                    resolverBaseClass = field.resolverBaseClass,
                    returnTypeName = field.returnTypeName,
                    hasArguments = field.hasArguments,
                    queryTypeName = field.queryTypeName,
                ),
            )
        }

        return ExecutionRegistry(
            version = REGISTRY_VERSION,
            executorFactory = executorFactory,
            nodes = nodes,
            fields = fields,
        )
    }

    private fun viaduct.tenant.codegen.ksp.SelectionsBlock.toEngineSelectionsBlock(): SelectionsBlock {
        return SelectionsBlock(
            selections = selections,
            variablesProviders = variablesProviders.map { provider ->
                VariableProviderEntry(
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

    private companion object {
        // Version of the ExecutionRegistry JSON schema — bump when the shape changes in a
        // backwards-incompatible way so the bootstrapper can reject stale artifacts.
        const val REGISTRY_VERSION = "1"

        val MAPPER: ObjectMapper = jacksonObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = AssembleTenantModuleConfigFile().main(args)
    }
}
