package com.example.main.service.viaduct

import io.micronaut.context.BeanContext
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.engine.runtime.tenantloading.ExecutionRegistryConfigSourceCollector
import viaduct.remote.config.RemoteResolverSelection
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId

@OptIn(ExperimentalApi::class, InternalApi::class)
class ViaductConfigurationTest {
    @Test
    fun `packaged registry index selects both remote tenants`() {
        val sources = ExecutionRegistryConfigSourceCollector.fromResources()
        val tenantNames = sources.map { it.tenantName }.toSet()
        val selection = RemoteResolverSelection.fromModuleConfigSources(tenantNames, sources)

        assertEquals(setOf("filmography", "universe"), selection.tenantNames)
        assertTrue("Character" in selection.nodeTypes)
        assertTrue("Species" in selection.nodeTypes)
        assertFalse("Film" in selection.nodeTypes)
        assertTrue("Query.allFilms" in selection.fieldCoordinates)
        assertTrue("Query.allSpecies" in selection.fieldCoordinates)
    }

    @Test
    fun `packaged schema and registry bootstrap both public schemas`() {
        BeanContext.run().use { context ->
            val configuration = ViaductConfiguration(MicronautTenantModuleInjectorFactory(context))
            val viaduct = configuration.providesViaduct(ProxyResolverFactory.NO_OP)

            val schemaIds = listOf(
                SchemaId.Scoped("publicSchema", setOf(DEFAULT_SCOPE_ID)),
                SchemaId.Scoped("publicSchemaWithExtras", setOf(DEFAULT_SCOPE_ID, EXTRAS_SCOPE_ID)),
            )
            for (schemaId in schemaIds) {
                val result = viaduct.executeAsync(
                    ExecutionInput.create("{ allFilms(limit: 1) { title } }"),
                    schemaId,
                ).get(30, TimeUnit.SECONDS)

                assertTrue(result.errors.isEmpty(), result.errors.toString())
                assertEquals(mapOf("allFilms" to listOf(mapOf("title" to "A New Hope"))), result.getData())

                val introspection = viaduct.executeAsync(
                    ExecutionInput.create("""{ __type(name: "Species") { fields { name } } }"""),
                    schemaId,
                ).get(30, TimeUnit.SECONDS)

                assertTrue(introspection.errors.isEmpty(), introspection.errors.toString())
                val species = requireNotNull(introspection.getData())["__type"] as Map<*, *>
                val fields = (species["fields"] as List<*>).map { (it as Map<*, *>)["name"] }
                assertTrue("name" in fields)
                assertEquals(schemaId.scopeIds.contains(EXTRAS_SCOPE_ID), "culturalNotes" in fields, schemaId.id)
            }
        }
    }
}
