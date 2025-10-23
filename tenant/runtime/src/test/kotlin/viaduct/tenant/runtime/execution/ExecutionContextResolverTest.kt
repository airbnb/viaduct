@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution

import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.api.FieldValue
import viaduct.api.context.ExecutionContext
import viaduct.api.context.FieldExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ResolverBase
import viaduct.api.internal.ResolverFor
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.engine.api.ViaductSchema
import viaduct.tenant.testing.DefaultAbstractResolverTestBase

class ExecutionContextResolverTest : DefaultAbstractResolverTestBase() {
    private val SCHEMA_SDL = """
     directive @resolver on FIELD_DEFINITION | OBJECT

     type Query {
       foo: String @resolver
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

            open suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String?>> = throw NotImplementedError("Query.field.batchResolve not implemented")
        }
    }

    class QueryFieldResolver : QueryResolvers.Field() {
        override suspend fun resolve(ctx: Context): String {
            val requestContext = ctx.requestContext as? RequestContext
                ?: throw RuntimeException("No request context")
            return requestContext.user
        }
    }

    data class RequestContext constructor(
        val user: String
    ) : ExecutionContext {

        override fun <T : NodeObject> globalIDFor(
            type: Type<T>,
            internalID: String
        ): GlobalID<T> {
            throw NotImplementedError("Query.field.resolve not implemented")
        }

        override val requestContext: Any?
            get() = throw NotImplementedError("Query.field.resolve not implemented")
    }

    override fun getSchema(): ViaductSchema {
        val schemaParser = SchemaParser()
        val typeDefinitionRegistry = schemaParser.parse(SCHEMA_SDL)

        val runtimeWiring = RuntimeWiring.newRuntimeWiring().build()
        val graphQLSchema = SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)

        return ViaductSchema(graphQLSchema)
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
