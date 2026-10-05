@file:Suppress("DEPRECATION")

package viaduct.engine.runtime.execution

import graphql.execution.preparsed.PreparsedDocumentProvider
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.DocumentProviderFactory
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

class CoroutineContextTest : CoroutineContextContract() {
    override fun createViaduct(
        schemaSDL: String,
        resolveField: suspend () -> String,
        documentProvider: PreparsedDocumentProvider,
    ): Viaduct {
        val module = EngineTestModule(schemaSDL) {
            for (type in listOf("Query", "Mutation")) {
                field(type to "requestIdentity") {
                    resolver { fn { _, _, _, _, _ -> resolveField() } }
                }
            }
        }
        return StandardViaduct.Builder()
            .withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
            .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
            .withSchemaConfiguration(SchemaConfiguration.fromSdl(schemaSDL))
            .withDocumentProviderFactory(DocumentProviderFactory { _, _ -> documentProvider })
            .withFlagManager(FlagManager.Disabled)
            .build()
    }
}
