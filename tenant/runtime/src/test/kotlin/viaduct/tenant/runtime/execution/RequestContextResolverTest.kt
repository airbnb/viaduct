@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution

import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.api.context.FieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ResolverBase
import viaduct.api.internal.ResolverFor
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Query
import viaduct.engine.api.ViaductSchema
import viaduct.tenant.testing.DefaultAbstractResolverTestBase

class RequestContextResolverTest : DefaultAbstractResolverTestBase() {
    private val SCHEMA_SDL = """
     type Query {
       foo: String
     }
    """

    object QueryResolvers {
        @ResolverFor(typeName = "Query", fieldName = "field")
        abstract class Field : ResolverBase<String?> {
            // Context wraps MockFieldExecutionContext (the concrete mock type)
            // This matches the generated pattern: value class wrapping the concrete execution context impl
            class Context(
                private val inner: FieldExecutionContext<Query, Query, Arguments.NoArguments, CompositeOutput.NotComposite>
            ) : FieldExecutionContext<Query, Query, Arguments.NoArguments, CompositeOutput.NotComposite> by inner,
                InternalContext by (inner as InternalContext)

            open suspend fun resolve(ctx: Context): String? = throw NotImplementedError("Query.field.resolve not implemented")
        }
    }

    private data class RequestContext constructor(
        val user: String
    )

    override fun getSchema(): ViaductSchema {
        val typeDefinitionRegistry = SchemaParser().parse(SCHEMA_SDL)
        val runtimeWiring = RuntimeWiring.newRuntimeWiring().build()

        return ViaductSchema(SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring))
    }

    class QueryFieldResolver : QueryResolvers.Field() {
        override suspend fun resolve(ctx: Context): String {
            val requestContext = ctx.requestContext as? RequestContext
                ?: throw RuntimeException("No request context provided")
            return requestContext.user
        }
    }

    @Test
    fun `test FooNameResolver returns string from RequestContext`(): Unit =
        runBlocking {
            val resolver = QueryFieldResolver()

            val result = runFieldResolver(
                requestContext = RequestContext(user = "user123"),
                resolver = resolver,
            )

            assertEquals("user123", result)
        }
}
