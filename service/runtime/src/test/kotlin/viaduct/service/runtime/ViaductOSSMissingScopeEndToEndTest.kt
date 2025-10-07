package viaduct.service.runtime

import com.google.inject.ProvisionException
import graphql.schema.idl.RuntimeWiring
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.scopes.errors.SchemaScopeValidationError
import viaduct.service.api.spi.Flag
import viaduct.service.api.spi.FlagManager

/**
 * Test if missing scope in a query fails as expected.
 */
@ExperimentalCoroutinesApi
class ViaductOSSMissingScopeEndToEndTest {
    private lateinit var subject: StandardViaduct
    private lateinit var schemaRegistryConfiguration: SchemaRegistryConfiguration

    val flagManager = object : FlagManager {
        override fun isEnabled(flag: Flag) = true
    }

    val wiring = RuntimeWiring.MOCKED_WIRING // TODO: Replace with injected OSS/modern-only wiring

    val sdl =
        """
        extend type Query @scope(to: ["publicScope"]) {
          helloWorld: String @resolver
        }

        extend type Query {
          scopeOmittedHelloWorld: String @resolver
        }
        """.trimIndent()

    @BeforeEach
    fun setUp() {
        schemaRegistryConfiguration = SchemaRegistryConfiguration.fromSdl(
            sdl,
            scopes = setOf(SchemaRegistryConfiguration.ScopeConfig("public", setOf("publicScope")))
        )
    }

    @Test
    fun `Missing scope in query must fail`() {
        val exception = assertThrows<SchemaScopeValidationError> {
            try {
                subject = StandardViaduct.Builder()
                    .withFlagManager(flagManager)
                    .withNoTenantAPIBootstrapper()
                    .withDataFetcherExceptionHandler(mockk())
                    .withSchemaRegistryConfiguration(schemaRegistryConfiguration)
                    .build()
            } catch (e: ProvisionException) {
                throw e.cause ?: e
            }
        }
        assertEquals(
            "No scope directives found from node: 'ObjectTypeExtensionDefinition{name='Query', implements=[], directives=[], fieldDefinitions=[FieldDefinition{name='scopeOmittedHelloWorld'",
            exception.message?.substring(0, 175)
        )
    }
}
