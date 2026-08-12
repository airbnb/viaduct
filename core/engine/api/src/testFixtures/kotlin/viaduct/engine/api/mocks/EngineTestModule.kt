@file:Suppress("ForbiddenImport")

package viaduct.engine.api.mocks

import graphql.language.AstPrinter
import viaduct.engine.api.Coordinate
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlockConfig
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor

class EngineTestModule(
    val fullSchema: ViaductSchema,
    val fieldResolverExecutors: Iterable<Pair<Coordinate, FieldResolverExecutor>> = emptyList(),
    val nodeResolverExecutors: Iterable<Pair<String, NodeResolverExecutor>> = emptyList(),
    val checkerExecutors: Map<Coordinate, CheckerExecutor> = emptyMap(),
    val typeCheckerExecutors: Map<String, CheckerExecutor> = emptyMap(),
) {
    companion object {
        /** Stable `apiName` for configs built by this test fixture. */
        const val API_NAME = "engine_test_module"

        operator fun invoke(
            schemaSDL: String,
            block: MockTenantModuleDSL<Unit>.() -> Unit,
        ): EngineTestModule = invoke(createSchemaWithWiring(schemaSDL), block)

        operator fun invoke(
            schemaWithWiring: ViaductSchema,
            block: MockTenantModuleDSL<Unit>.() -> Unit,
        ): EngineTestModule = MockTenantModuleDSL(schemaWithWiring, Unit).apply(block).createEngineTestModule()
    }

    fun buildExecutionRegistryConfigFile(): ExecutionRegistryConfigFile {
        val fieldEntries = fieldResolverExecutors.map { (coord, executor) ->
            requireFieldInSchema(coord)
            FieldEntryConfig(
                typeName = coord.first,
                fieldName = coord.second,
                isBatching = executor.isBatching,
                isSelective = executor.isSelective,
                attribution = executor.metadata.name,
                objectSelections = executor.objectSelectionSet?.toSelectionsBlockConfig(),
                querySelections = executor.querySelectionSet?.toSelectionsBlockConfig(),
                tenantAPIData = mapOf("resolver" to executor),
            )
        }
        val nodeEntries = nodeResolverExecutors.map { (typeName, executor) ->
            requireNodeInSchema(typeName)
            NodeEntryConfig(
                typeName = typeName,
                isBatching = executor.isBatching,
                isSelective = executor.isSelective,
                attribution = executor.metadata.name,
                tenantAPIData = mapOf("resolver" to executor),
            )
        }
        return ExecutionRegistryConfigFile(
            version = "1",
            executorFactory = EngineTestModuleExecutorFactory::class.java.name,
            apiName = API_NAME,
            fields = fieldEntries,
            nodes = nodeEntries,
        )
    }

    private fun RequiredSelectionSet.toSelectionsBlockConfig() =
        SelectionsBlockConfig(
            selections = "fragment _ on ${selections.typeName} ${AstPrinter.printAst(selections.selections)}",
        )

    private fun requireFieldInSchema(coord: Coordinate) {
        val objectType = requireNotNull(fullSchema.schema.getObjectType(coord.first)) {
            "EngineTestModule: type '${coord.first}' not found in fullSchema. Cannot register resolver for ${coord.first}.${coord.second}."
        }
        requireNotNull(objectType.getFieldDefinition(coord.second)) {
            "EngineTestModule: field '${coord.second}' not found on type '${coord.first}' in fullSchema. Cannot register resolver for ${coord.first}.${coord.second}."
        }
    }

    private fun requireNodeInSchema(typeName: String) {
        val objectType = requireNotNull(fullSchema.schema.getObjectType(typeName)) {
            "EngineTestModule: type '$typeName' not found in fullSchema. Cannot register node resolver."
        }
        requireNotNull(fullSchema.schema.getType("Node")) {
            "EngineTestModule: schema does not define Node interface. Cannot register node resolver for '$typeName'."
        }
        require(objectType.interfaces.any { it.name == "Node" }) {
            "EngineTestModule: type '$typeName' does not implement Node interface in fullSchema. Cannot register node resolver."
        }
    }
}

class EngineTestModuleExecutorFactory : ExecutorFactory {
    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema,
    ): FieldResolverExecutor = configData.tenantAPIData["resolver"] as FieldResolverExecutor

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema,
    ): NodeResolverExecutor = configData.tenantAPIData["resolver"] as NodeResolverExecutor
}
