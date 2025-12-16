@file:Suppress("Detekt.MatchingDeclarationName")

package viaduct.arbitrary.graphql

import graphql.Scalars
import graphql.TypeResolutionEnvironment
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.TypeResolver
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaParser
import io.kotest.property.Arb
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import viaduct.arbitrary.common.Config

/** Methods for generating GraphQLSchema instances */
internal object SchemaGenerator {
    private val emptyQuery: GraphQLObjectType =
        GraphQLObjectType
            .newObject()
            .name("EmptyQuery")
            .field(
                GraphQLFieldDefinition
                    .newFieldDefinition()
                    .name("placeholder")
                    .type(Scalars.GraphQLInt)
                    .build()
            ).build()

    private fun codeRegistry(types: GraphQLTypes): GraphQLCodeRegistry {
        val noopResolver =
            object : TypeResolver {
                override fun getType(env: TypeResolutionEnvironment?): GraphQLObjectType = throw UnsupportedOperationException("not implemented")
            }

        return GraphQLCodeRegistry
            .newCodeRegistry()
            .also {
                types.interfaces.forEach { (name, _) ->
                    it.typeResolver(name, noopResolver)
                }
                types.unions.forEach { (name, _) ->
                    it.typeResolver(name, noopResolver)
                }
            }.build()
    }

    /** Generate a GraphQLSchema from a GraphQLTypes */
    fun mkSchema(types: GraphQLTypes): Result<GraphQLSchema> =
        Result.runCatching {
            GraphQLSchema
                .newSchema()
                .query(
                    types.objects.entries
                        .firstOrNull()
                        ?.value ?: emptyQuery
                ).additionalTypes(types.interfaces.values.toSet())
                .additionalTypes(types.objects.values.toSet())
                .additionalTypes(types.inputs.values.toSet())
                .additionalTypes(types.unions.values.toSet())
                .additionalTypes(types.scalars.values.toSet())
                .additionalTypes(types.enums.values.toSet())
                .additionalDirectives(types.directives.values.toSet())
                .codeRegistry(codeRegistry(types))
                .build()
        }

    fun mkSchema(sdl: String): GraphQLSchema {
        val tdr = SchemaParser().parse(sdl)
        return graphql.schema.idl
            .SchemaGenerator()
            .makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
    }
}

/** Generate arbitrary GraphQLSchemas from arbitrary Configs */
fun Arb.Companion.graphQLSchema(cfg: Arb<Config>): Arb<GraphQLSchema> = cfg.flatMap(::graphQLSchema)

/** Generate arbitrary GraphQLSchemas from a static Config */
fun Arb.Companion.graphQLSchema(cfg: Config = Config.default): Arb<GraphQLSchema> = graphQLSchema(Arb.graphQLTypes(cfg))

/** Generate arbitrary GraphQLSchemas from arbitrary GraphQLTypes */
@JvmName("arbGraphQLSchema")
fun Arb.Companion.graphQLSchema(types: Arb<GraphQLTypes>): Arb<GraphQLSchema> =
    types.map {
        SchemaGenerator.mkSchema(it).getOrThrow()
    }
