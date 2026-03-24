package viaduct.graphql.scopes.visitors

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator

fun toSchema(sdl: String) = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(sdl))
