@file:Suppress("DEPRECATION")

package viaduct.service.runtime

import viaduct.engine.SchemaFactory
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockExecutorCodeInjector

/** Explicit null-valued fixtures for tests of schema routing, rather than resolver execution. */
internal fun StandardViaduct.Builder.withNullQueryResolvers(config: SchemaConfiguration): StandardViaduct.Builder {
    val schema = requireNotNull(config.fullSchemaConfig).build(SchemaFactory())
    val module = EngineTestModule(schema) {
        schema.schema.queryType.fieldDefinitions
            .filter { it.name !in setOf("node", "nodes") }
            .forEach { fieldWithValue("Query" to it.name, null) }
    }
    return withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
        .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
}
