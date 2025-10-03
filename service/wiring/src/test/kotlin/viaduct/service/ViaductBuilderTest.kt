package viaduct.service

import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.spi.Flag
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.SchemaRegistryConfiguration

class ViaductBuilderTest {
    val schema = mkSchema(
        """
             directive @resolver on FIELD_DEFINITION | OBJECT
             directive @backingData(class: String!) on FIELD_DEFINITION

             type Query @scope(to: ["*"]) {
              _: String @deprecated
             }
             type Mutation @scope(to: ["*"]) {
               _: String @deprecated
             }
             type Subscription @scope(to: ["*"]) {
               _: String @deprecated
             }

             directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

             extend type Query @scope(to: ["publicScope"]) {
              helloWorld: String @resolver
             }
        """
    )

    val flagManager = object : FlagManager {
        override fun isEnabled(flag: Flag) = true
    }

    @Test
    fun testBuilderProxy() {
        val schemaRegistryConfiguration = SchemaRegistryConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaRegistryConfiguration.ScopeConfig("public", setOf("publicScope")))
        )
        ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withSchemaRegistryConfiguration(schemaRegistryConfiguration)
            .standardNodeBehavior(false)
            .build().let {
                assertNotNull(it)
            }
    }

    private fun mkSchema(sdl: String): ViaductSchema = ViaductSchema(SchemaGenerator().makeExecutableSchema(SchemaParser().parse(sdl), RuntimeWiring.MOCKED_WIRING))
}
