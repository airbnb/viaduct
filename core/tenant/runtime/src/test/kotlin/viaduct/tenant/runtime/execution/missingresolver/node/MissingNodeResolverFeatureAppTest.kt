package viaduct.tenant.runtime.execution.missingresolver.node

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.missingresolver.node.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.runtime.fixtures.MissingResolverImplementationException

/**
 * Tests that FeatureAppTestBase validates resolver completeness at build time,
 * producing a clear error message when a @resolver-declared node type
 * is missing its @Resolver implementation class.
 */
class MissingNodeResolverFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | type Widget implements Node @resolver {
        |   id: ID!
        |   label: String!
        | }
        |
        | extend type Query {
        |   widget(id: String!): Widget! @resolver
        | }
        | #END_SCHEMA
    """.trimMargin()

    // Provide the field resolver but NOT the node resolver
    @Resolver
    class WidgetQueryResolver : QueryResolvers.Widget() {
        override suspend fun resolve(ctx: Context): Widget {
            val globalId = ctx.globalIDFor(Widget.Reflection, ctx.arguments.id)
            return ctx.nodeFor(globalId)
        }
    }

    @Test
    fun `missing node resolver produces a clear error message`() {
        val exception = assertThrows<MissingResolverImplementationException> {
            tryBuildViaductService()
        }
        assertThat(exception.message)
            .contains("Node(Widget)")
    }
}
