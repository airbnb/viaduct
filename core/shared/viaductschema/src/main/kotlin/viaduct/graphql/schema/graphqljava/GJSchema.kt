@file:Suppress("ktlint:standard:indent")

package viaduct.graphql.schema.graphqljava

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import java.net.URL
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.BitVector
import viaduct.utils.timer.Timer

/**
 * Factory functions for creating [SchemaWithData] from graphql-java [GraphQLSchema].
 *
 * This is the validated schema path - [GraphQLSchema] objects are fully
 * validated by graphql-java, making this the safest option but also
 * slower to construct than the "raw" path (see [gjSchemaRawFromRegistry]).
 *
 * Each [SchemaWithData.Def.holder] stores the corresponding graphql-java schema
 * element under a private key.
 *
 * Use factory functions like [gjSchemaFromSchema], [gjSchemaFromRegistry],
 * [gjSchemaFromFiles], or [gjSchemaFromURLs] to create instances.
 */

/** Convert collection of .graphqls files into a schema. */
internal fun gjSchemaFromURLs(inputFiles: List<URL>): SchemaWithData = gjSchemaFromRegistry(readTypesFromURLs(inputFiles))

internal fun gjSchemaFromFiles(
    inputFiles: List<File>,
    timer: Timer = Timer(),
): SchemaWithData {
    val typeDefRegistry = timer.time("readTypesFromFiles") { readTypesFromFiles(inputFiles) }
    return gjSchemaFromRegistry(typeDefRegistry, timer)
}

/** Convert a graphql-java TypeDefinitionRegistry into a schema. */
internal fun gjSchemaFromRegistry(
    registry: TypeDefinitionRegistry,
    timer: Timer = Timer(),
): SchemaWithData {
    val unexecutableSchema =
        timer.time("makeUnexecutableSchema") {
            UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
        }
    return timer.time("fromSchema") { gjSchemaFromSchema(unexecutableSchema) }
}

/** Create a ViaductSchema from a validated graphql-java schema. */
internal fun gjSchemaFromSchema(schema: GraphQLSchema): SchemaWithData {
    val result = SchemaWithData()

    // Phase 1: Create all TypeDef and Directive shells with the underlying definition.
    val types = mutableMapOf<String, SchemaWithData.TypeDef>()
    for (def in schema.allTypesAsList) {
        // Skip introspection types - they're graphql-java implementation details
        if (def.name.startsWith("__")) continue
        val typeDef = when (def) {
            is GraphQLScalarType -> SchemaWithData.Scalar(result, def.name, gjSchemaHolder(def))
            is GraphQLEnumType -> SchemaWithData.Enum(result, def.name, gjSchemaHolder(def))
            is GraphQLUnionType -> SchemaWithData.Union(result, def.name, gjSchemaHolder(def))
            is GraphQLInterfaceType -> SchemaWithData.Interface(result, def.name, gjSchemaHolder(def))
            is GraphQLObjectType -> SchemaWithData.Object(result, def.name, gjSchemaHolder(def))
            is GraphQLInputObjectType -> SchemaWithData.Input(result, def.name, gjSchemaHolder(def))
            else -> throw RuntimeException("Unexpected GraphQL type: $def")
        }
        types[def.name] = typeDef
    }

    val directives = schema.directives.associate { it.name to SchemaWithData.Directive(result, it.name, gjSchemaHolder(it)) }

    // Phase 2: Create decoder and populate all types and directives
    val decoder = GraphQLSchemaDecoder(schema, types, directives)

    types.values.forEach { typeDef ->
        when (typeDef) {
            is SchemaWithData.Scalar -> typeDef.populate(decoder.createScalarExtensions(typeDef), typeDef.gjDef.description)
            is SchemaWithData.Enum -> typeDef.populate(decoder.createEnumExtensions(typeDef), typeDef.gjDef.description)
            is SchemaWithData.Union -> typeDef.populate(decoder.createUnionExtensions(typeDef), typeDef.gjDef.description)
            is SchemaWithData.Interface -> typeDef.populate(
                decoder.createInterfaceExtensions(typeDef),
                decoder.computePossibleObjectTypes(typeDef),
                typeDef.gjDef.description
            )
            is SchemaWithData.Object -> typeDef.populate(
                decoder.createObjectExtensions(typeDef),
                decoder.computeUnions(typeDef),
                typeDef.gjDef.description
            )
            is SchemaWithData.Input -> typeDef.populate(decoder.createInputExtensions(typeDef), typeDef.gjDef.description)
        }
    }

    directives.values.forEach { directive ->
        decoder.populate(directive)
    }

    // Determine root types and populate schema
    val queryTypeDef = rootDef(types, schema.queryType?.name, "Query")
        ?: throw IllegalStateException("Query name (${schema.queryType?.name}) not found.")
    val mutationTypeDef = rootDef(types, schema.mutationType?.name, "Mutation")
    val subscriptionTypeDef = rootDef(types, schema.subscriptionType?.name, "Subscription")

    result.populate(directives, types, queryTypeDef, mutationTypeDef, subscriptionTypeDef)
    return result
}

private fun rootDef(
    types: Map<String, SchemaWithData.TypeDef>,
    name: String?,
    stdName: String
): SchemaWithData.Object? {
    val result = name?.let { types[it] }
    if (result != null) {
        require(result is SchemaWithData.Object) { "$stdName type ($name) is not an object type." }
        return result
    }
    return null
}

// Extension function toTypeExpr(wrappers, baseString) is provided by Utils.kt

// Internal for testing (GJSchemaCheck)
internal fun SchemaWithData.toTypeExpr(gtype: GraphQLType): ViaductSchema.TypeExpr<SchemaWithData.TypeDef> {
    var baseTypeNullable = true
    var listNullable = ViaductSchema.TypeExpr.NO_WRAPPERS

    var t = gtype
    if (GraphQLTypeUtil.isWrapped(t)) {
        val wrapperBuilder = BitVector.Builder()
        do {
            if (GraphQLTypeUtil.isList(t)) {
                wrapperBuilder.add(1L, 1)
                t = GraphQLTypeUtil.unwrapOne(t)
            } else if (GraphQLTypeUtil.isNonNull(t)) {
                t = GraphQLTypeUtil.unwrapOne(t)
                if (GraphQLTypeUtil.isList(t)) {
                    wrapperBuilder.add(0L, 1)
                    t = GraphQLTypeUtil.unwrapOne(t)
                } else if (GraphQLTypeUtil.isWrapped(t)) {
                    throw IllegalStateException("Unexpected GraphQL wrapping $gtype.")
                } else {
                    baseTypeNullable = false
                }
            } else {
                throw IllegalStateException("Unexpected GraphQL wrapper $gtype.")
            }
        } while (GraphQLTypeUtil.isWrapped(t))
        listNullable = wrapperBuilder.build()
    }

    val baseTypeDefName = GraphQLTypeUtil.unwrapAll(gtype).name
    val baseTypeDef = types[baseTypeDefName]
        ?: error("Type not found: $baseTypeDefName")
    return ViaductSchema.TypeExpr(baseTypeDef, baseTypeNullable, listNullable)
}
