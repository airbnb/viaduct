package viaduct.remote.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.service.api.spi.InputStreamSource

class RemoteResolverSelectionTest {
    @Test
    fun `derives all resolver IDs for selected tenants`() {
        val selection =
            RemoteResolverSelection.fromModuleConfigSources(
                selectedTenantNames = setOf("data/user"),
                moduleConfigSources =
                    listOf(
                        registrySource(
                            tenantName = "data/user",
                            nodeType = "User",
                            fieldCoordinate = "User.name",
                        ),
                        registrySource(
                            tenantName = "data/listing",
                            nodeType = "Listing",
                            fieldCoordinate = "Listing.title",
                        ),
                    ),
            )

        assertThat(selection.tenantNames).containsExactly("data/user")
        assertThat(selection.nodeTypes).containsExactly("User")
        assertThat(selection.fieldCoordinates).containsExactly("User.name")
    }

    @Test
    fun `identifies a selected tenant without a registry config`() {
        assertThatThrownBy {
            RemoteResolverSelection.fromModuleConfigSources(
                selectedTenantNames = setOf("data/user"),
                moduleConfigSources =
                    listOf(
                        registrySource(
                            tenantName = "data/listing",
                            nodeType = "Listing",
                            fieldCoordinate = "Listing.title",
                        )
                    ),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("No execution registry config found for selected tenant 'data/user'")
    }

    @Test
    fun `excludes selective resolvers without rejecting tenant`() {
        val selection =
            RemoteResolverSelection.fromModuleConfigSources(
                selectedTenantNames = setOf("data/user"),
                moduleConfigSources =
                    listOf(
                        registrySource(
                            tenantName = "data/user",
                            nodeType = "User",
                            fieldCoordinate = "User.name",
                            isSelective = true,
                        )
                    ),
            )

        assertThat(selection.tenantNames).containsExactly("data/user")
        assertThat(selection.nodeTypes).isEmpty()
        assertThat(selection.fieldCoordinates).containsExactly("User.name")
    }

    @Test
    fun `non-kotlin configs are not proxied because the remote process does not load them`() {
        // A tenant's second-API config lives beside its `kotlin` one on the classpath, but RRS only loads
        // <pkg>.json — proxying those coordinates would route them to a process that cannot resolve
        // them.
        val selection = RemoteResolverSelection.fromModuleConfigSources(
            selectedTenantNames = setOf("alpha"),
            moduleConfigSources = listOf(
                registrySource(tenantName = "alpha", nodeType = "User", fieldCoordinate = "User.name"),
                registrySource(
                    tenantName = "alpha",
                    nodeType = "Legacy",
                    fieldCoordinate = "Legacy.derived",
                    apiName = "other",
                ),
            ),
        )

        assertThat(selection.nodeTypes).containsExactly("User")
        assertThat(selection.fieldCoordinates).containsExactly("User.name")
    }

    private fun registrySource(
        tenantName: String,
        nodeType: String,
        fieldCoordinate: String,
        isSelective: Boolean = false,
        apiName: String = "kotlin",
    ): ModuleConfigSource {
        val (fieldType, fieldName) = fieldCoordinate.split(".", limit = 2)
        return ModuleConfigSource.from(
            InputStreamSource.fromString(
                """
                {
                  "version": "1",
                  "tenantName": "$tenantName",
                  "apiName": "$apiName",
                  "executorFactory": "unused",
                  "nodes": [{
                    "typeName": "$nodeType",
                    "isBatching": false,
                    "isSelective": $isSelective,
                    "attribution": "test",
                    "tenantAPIData": {}
                  }],
                  "fields": [{
                    "typeName": "$fieldType",
                    "fieldName": "$fieldName",
                    "isBatching": false,
                    "isSelective": false,
                    "attribution": "test",
                    "tenantAPIData": {}
                  }]
                }
                """.trimIndent(),
                name = tenantName,
            )
        )
    }
}
