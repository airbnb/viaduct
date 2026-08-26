package viaduct.graphql.schema.graphqljava

import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLUnionType
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.HMap

private val gjSchemaKey = HMap.Key.of<Any?>("GJSchema")

internal fun gjSchemaHolder(value: Any?): HMap =
    HMap.Builder()
        .put(gjSchemaKey, value)
        .build()

/**
 * The underlying graphql-java element.
 *
 * @throws NoSuchElementException if this definition was not created from a
 * graphql-java [graphql.schema.GraphQLSchema].
 */
val ViaductSchema.Def.gjDef: GraphQLNamedSchemaElement
    get() = holder[gjSchemaKey] as GraphQLNamedSchemaElement

/** The underlying graphql-java scalar type. */
val ViaductSchema.Scalar.gjDef: GraphQLScalarType
    get() = holder[gjSchemaKey] as GraphQLScalarType

/** The underlying graphql-java enum type. */
val ViaductSchema.Enum.gjDef: GraphQLEnumType
    get() = holder[gjSchemaKey] as GraphQLEnumType

/** The underlying graphql-java union type. */
val ViaductSchema.Union.gjDef: GraphQLUnionType
    get() = holder[gjSchemaKey] as GraphQLUnionType

/** The underlying graphql-java interface type. */
val ViaductSchema.Interface.gjDef: GraphQLInterfaceType
    get() = holder[gjSchemaKey] as GraphQLInterfaceType

/** The underlying graphql-java object type. */
val ViaductSchema.Object.gjDef: GraphQLObjectType
    get() = holder[gjSchemaKey] as GraphQLObjectType

/** The underlying graphql-java input object type. */
val ViaductSchema.Input.gjDef: GraphQLInputObjectType
    get() = holder[gjSchemaKey] as GraphQLInputObjectType

/** The underlying graphql-java directive. */
val ViaductSchema.Directive.gjDef: GraphQLDirective
    get() = holder[gjSchemaKey] as GraphQLDirective
