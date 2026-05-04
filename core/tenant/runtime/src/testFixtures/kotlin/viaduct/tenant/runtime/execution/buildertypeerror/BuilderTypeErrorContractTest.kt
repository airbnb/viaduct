package viaduct.tenant.runtime.execution.buildertypeerror

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

/**
 * Contract test for GRT builder type checking on list element types.
 *
 * JVM type erasure means List<Item> and List<Tag> are indistinguishable at runtime,
 * so a typed builder setter like `tags(listOf(wrongTypeItem))` would silently accept
 * the wrong element type. The builder must validate object types at build time
 * to surface a clear TenantUsageException at the resolver level rather than a
 * cryptic failure during field completion.
 */
@TestSchema(
    """
    extend type Query {
      "Return a Container; the resolver must put a list of Tags into the tags field"
      container: Container @resolver
    }

    type Container {
      tags: [Tag]
    }

    type Tag {
      value: String
    }

    type Item {
      name: String
    }
"""
)
abstract class BuilderTypeErrorContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `putting wrong object type into list field produces TenantUsageException`() {
        val result = execute(
            query = """
                query {
                    container {
                        tags {
                            value
                        }
                    }
                }
            """.trimIndent()
        )

        assertEquals(1, result.errors.size)
        val extensions = result.errors[0].extensions
        assertEquals("viaduct.errors.TenantUsageException", extensions["fullyQualifiedErrorClass"])
        assertEquals("container", extensions["fieldName"])
        assertEquals("Query", extensions["parentType"])
    }
}
