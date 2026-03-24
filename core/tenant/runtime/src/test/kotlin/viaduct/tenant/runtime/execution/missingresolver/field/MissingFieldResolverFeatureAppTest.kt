package viaduct.tenant.runtime.execution.missingresolver.field

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.missingresolver.field.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.runtime.fixtures.MissingResolverImplementationException

/**
 * Tests that FeatureAppTestBase validates resolver completeness at build time,
 * producing a clear error message when a @resolver-declared field
 * is missing its @Resolver implementation class.
 */
class MissingFieldResolverFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | extend type Query {
        |   implemented: String! @resolver
        |   forgotten: String! @resolver
        | }
        | #END_SCHEMA
    """.trimMargin()

    // Only implement one of the two resolvers — "forgotten" is intentionally missing
    @Resolver
    class ImplementedResolver : QueryResolvers.Implemented() {
        override suspend fun resolve(ctx: Context): String = "present"
    }

    @Test
    fun `missing field resolver produces a clear error message`() {
        val exception = assertThrows<MissingResolverImplementationException> {
            tryBuildViaductService()
        }
        assertThat(exception.message)
            .contains("Query.forgotten")
            .contains("@Resolver")
    }
}
