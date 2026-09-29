package model.lowering

import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import viaduct.graphql.schema.ViaductSchema

internal fun graphQLSchema(sdl: String): GraphQLSchema =
    UnExecutableSchemaGenerator.makeUnExecutableSchema(
        SchemaParser().parse(sdl.trimIndent()),
    )

internal fun ViaductSchema.requireType(name: String): ViaductSchema.TypeDef = requireNotNull(types[name]) { "Missing type $name" }

internal fun ViaductSchema.requireRecord(name: String): ViaductSchema.Record =
    requireType(name) as? ViaductSchema.Record
        ?: error("$name is not a record")

internal fun ViaductSchema.requireField(
    typeName: String,
    fieldName: String,
): ViaductSchema.Field =
    requireNotNull(requireRecord(typeName).field(fieldName)) {
        "Missing field $typeName.$fieldName"
    }

internal fun ViaductSchema.TypeExpr<*>.nullabilityShape(): List<Boolean> = (0..listDepth).map(::nullableAtDepth)

internal fun Collection<ViaductSchema.AppliedDirective<*>>.semanticValues(): List<Pair<String, Map<String, ViaductSchema.Literal>>> = map { directive -> directive.name to directive.arguments }
