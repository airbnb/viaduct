package viaduct.graphql.schema.graphqljava.extensions

import graphql.schema.GraphQLSchema
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.net.URL
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.gjSchemaFromFiles
import viaduct.graphql.schema.graphqljava.gjSchemaFromRegistry
import viaduct.graphql.schema.graphqljava.gjSchemaFromSchema
import viaduct.graphql.schema.graphqljava.gjSchemaFromURLs
import viaduct.graphql.schema.graphqljava.gjSchemaRawFromFiles
import viaduct.graphql.schema.graphqljava.gjSchemaRawFromRegistry
import viaduct.graphql.schema.graphqljava.gjSchemaRawFromSDL
import viaduct.graphql.schema.graphqljava.gjSchemaRawFromURLs
import viaduct.utils.timer.Timer

/**
 * Creates a [GraphQLSchema] from SDL and uses it to create a
 * [ViaductSchema].
 */
fun ViaductSchema.Companion.fromGraphQLSchema(inputFiles: List<URL>,): ViaductSchema = gjSchemaFromURLs(inputFiles)

/**
 * Creates a [GraphQLSchema] from SDL and uses it to create a
 * [ViaductSchema].
 */
fun ViaductSchema.Companion.fromGraphQLSchema(
    inputFiles: List<File>,
    timer: Timer = Timer(),
): ViaductSchema = gjSchemaFromFiles(inputFiles, timer)

/**
 * Creates a [GraphQLSchema] from [registry] and uses it to
 * create a [ViaductSchema].
 */
fun ViaductSchema.Companion.fromGraphQLSchema(
    registry: TypeDefinitionRegistry,
    timer: Timer = Timer(),
): ViaductSchema = gjSchemaFromRegistry(registry, timer)

/** Creates a [ViaductSchema] from a [GraphQLSchema]. */
fun ViaductSchema.Companion.fromGraphQLSchema(schema: GraphQLSchema,): ViaductSchema = gjSchemaFromSchema(schema)

/**
 * Creates a [TypeDefinitionRegistry] from SDL and uses it to
 * create a [ViaductSchema].
 */
fun ViaductSchema.Companion.fromTypeDefinitionRegistry(inputFiles: List<URL>,): ViaductSchema = gjSchemaRawFromURLs(inputFiles)

/**
 * Creates a [TypeDefinitionRegistry] from SDL and uses it to
 * create a [ViaductSchema].
 */
fun ViaductSchema.Companion.fromTypeDefinitionRegistry(
    inputFiles: List<File>,
    timer: Timer = Timer(),
): ViaductSchema = gjSchemaRawFromFiles(inputFiles, timer)

/**
 * Create a [ViaductSchema] from a [TypeDefinitionRegistry]
 */
fun ViaductSchema.Companion.fromTypeDefinitionRegistry(
    registry: TypeDefinitionRegistry,
    timer: Timer = Timer(),
): ViaductSchema = gjSchemaRawFromRegistry(registry, timer)

/**
 * Creates a [ViaductSchema] from a GraphQL SDL string.
 */
fun ViaductSchema.Companion.fromTypeDefinitionRegistry(
    sdl: String,
    timer: Timer = Timer(),
): ViaductSchema = gjSchemaRawFromSDL(sdl, timer)
