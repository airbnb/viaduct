package viaduct.tenant.runtime.execution.missingresolver.node

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.testing.TestSchema
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.runtime.fixtures.MissingResolverImplementationException

/**
 * Contract test that verifies a clear error message is produced when a
 * @resolver-declared node type is missing its @Resolver implementation class.
 *
 * Extend this class and provide only the field resolver — the node resolver
 * is intentionally missing.
 */
@TestSchema(
    """
    type Widget implements Node @resolver {
      id: ID!
      label: String!
    }

    extend type Query {
      widget(id: String!): Widget! @resolver
    }
    """
)
abstract class MissingNodeResolverContractTest : FeatureAppTestBase() {
    @Test
    fun `missing node resolver produces a clear error message`() {
        val exception = assertThrows<MissingResolverImplementationException> {
            tryBuildViaductService()
        }
        assertThat(exception.message)
            .contains("Node(Widget)")
    }
}
