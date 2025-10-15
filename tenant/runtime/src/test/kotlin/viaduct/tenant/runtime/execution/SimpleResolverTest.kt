@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution

import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.api.FieldValue
import viaduct.api.context.FieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ResolverBase
import viaduct.api.internal.ResolverFor
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Query
import viaduct.engine.api.ViaductSchema
import viaduct.tenant.testing.DefaultAbstractResolverTestBase

class SimpleResolverTest : DefaultAbstractResolverTestBase() {
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
            return "123"
        }
    }

    override fun getSchema(): ViaductSchema {
        val schemaParser = SchemaParser()
        val typeDefinitionRegistry = schemaParser.parse(SCHEMA_SDL)

        val runtimeWiring = RuntimeWiring.newRuntimeWiring().build()
        val graphQLSchema = SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)

        return ViaductSchema(graphQLSchema)
    }

    @Test
    fun `test FooNameResolver returns greeting with name`(): Unit =
        runBlocking {
            val resolver = QueryFieldResolver()

            val result = runFieldResolver(
                resolver = resolver,
            )

            assertEquals("123", result)
        }
}
