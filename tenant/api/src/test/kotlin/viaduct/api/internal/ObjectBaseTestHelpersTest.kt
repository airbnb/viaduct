package viaduct.api.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.ViaductTenantUsageException
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.api.schemautils.SchemaUtils
import viaduct.api.testschema.O1

@OptIn(ExperimentalCoroutinesApi::class)
class ObjectBaseTestHelpersTest {
    private val gqlSchema = SchemaUtils.getSchema()
    private val internalContext = MockInternalContext.mk(gqlSchema, "viaduct.api.testschema")
    private val executionContext = internalContext.executionContext

    @Test
    fun `internal-only builder put with alias`() =
        runBlockingTest {
            val o1Builder = O1.Builder(executionContext)
            val o1 = ObjectBaseTestHelpers.putWithAlias(o1Builder, "stringField", "aliasedStringField", "hello")
                .build()

            assertEquals("hello", o1.getStringField("aliasedStringField"))
            // The "normal", unaliased field is not set.
            assertThrows<ViaductTenantUsageException> {
                runBlockingTest {
                    o1.getStringField()
                }
            }
        }
}
