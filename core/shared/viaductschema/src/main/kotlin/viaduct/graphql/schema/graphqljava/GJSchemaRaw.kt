package viaduct.graphql.schema.graphqljava

import graphql.language.Description
import graphql.language.Directive
import graphql.language.DirectiveDefinition
import graphql.language.DirectiveLocation
import graphql.language.EnumTypeDefinition
import graphql.language.ImplementingTypeDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InputValueDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.SchemaDefinition
import graphql.language.StringValue
import graphql.language.Type
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import graphql.language.Value
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.net.URL
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.BitVector
import viaduct.utils.timer.Timer

/**
 * Factory functions for creating [SchemaWithData] from a [TypeDefinitionRegistry].
 *
 * This is an alternative to [gjSchemaFromSchema], which uses the validated `graphql.schema`:
 * [TypeDefinitionRegistry]s are faster to create than [GraphQLSchema]s
 * but are not validated, so there's a performance/safety tradeoff.
 *
 * The auxiliary data stored in [SchemaWithData.Def.data] is the corresponding
 * graphql.language node. For TypeDefs, it's a [TypeDefData] containing both
 * the base definition and extension definitions.
 *
 * Use factory functions like [gjSchemaRawFromSDL], [gjSchemaRawFromFiles],
 * [gjSchemaRawFromURLs], or [gjSchemaRawFromRegistry] to create instances.
 */

/** Convert collection of .graphqls files into a schema. */
internal fun gjSchemaRawFromURLs(inputFiles: List<URL>): SchemaWithData = gjSchemaRawFromRegistry(readTypesFromURLs(inputFiles))

internal fun gjSchemaRawFromFiles(
    inputFiles: List<File>,
    timer: Timer = Timer(),
): SchemaWithData {
    val typeDefRegistry = timer.time("readTypesFromFiles") { readTypesFromFiles(inputFiles) }
    return gjSchemaRawFromRegistry(typeDefRegistry, timer)
}

internal fun gjSchemaRawFromSDL(
    sdl: String,
    timer: Timer = Timer(),
): SchemaWithData {
    val typeDefRegistry = timer.time("readTypes") { readTypes(sdl) }
    return gjSchemaRawFromRegistry(typeDefRegistry, timer)
}

/**
 * Convert a graphql-java TypeDefinitionRegistry into a schema.
 *
 * Root types are determined by first checking the `schema` definition
 * ([graphql.language.SchemaDefinition]) found while parsing the schema,
 * and falling back to the standard names ("Query", "Mutation", "Subscription")
 * if no schema definition exists.
 */
internal fun gjSchemaRawFromRegistry(
    registry: TypeDefinitionRegistry,
    timer: Timer = Timer(),
): SchemaWithData =
    timer.time("fromSchema") {
        // graphql-java assumes these get created during language->schema translation
        val deprecatedDirectiveDef =
            DirectiveDefinition
                .newDirectiveDefinition()
                .name("deprecated")
                .inputValueDefinition(
                    InputValueDefinition("reason", NonNullType(TypeName("String")), StringValue("No longer supported"))
                ).directiveLocations(
                    listOf(
                        DirectiveLocation("FIELD_DEFINITION"),
                        DirectiveLocation("ARGUMENT_DEFINITION"),
                        DirectiveLocation("INPUT_FIELD_DEFINITION"),
                        DirectiveLocation("ENUM_VALUE")
                    )
                ).build()
        registry.add(deprecatedDirectiveDef)
        val specifiedByDirectiveDef =
            DirectiveDefinition
                .newDirectiveDefinition()
                .name("specifiedBy")
                .inputValueDefinition(
                    InputValueDefinition("url", NonNullType(TypeName("String")))
                ).directiveLocations(listOf(DirectiveLocation("SCALAR")))
                .build()
        registry.add(specifiedByDirectiveDef)
        val oneOfDirectiveDef =
            DirectiveDefinition
                .newDirectiveDefinition()
                .name("oneOf")
                .description(
                    Description(
                        "Indicates an Input Object is a OneOf Input Object.",
                        null,
                        false
                    )
                ).directiveLocations(listOf(DirectiveLocation("INPUT_OBJECT")))
                .build()
        registry.add(oneOfDirectiveDef)
        // Take a first pass, translating unions and accumulating a
        // name-to-list-of-unions map for interfaces and object types
        val unionsMap = mutableMapOf<String, MutableSet<String>>()
        val unionDefs: List<UnionTypeDefinition> =
            registry.getTypes(UnionTypeDefinition::class.java) + registry.unionTypeExtensions().values.flatten()
        for (def in unionDefs) {
            def.memberTypes.forEach {
                unionsMap
                    .getOrPut((it as TypeName).name) {
                        mutableSetOf()
                    }.add(def.name)
            }
        }
        val membersMap = mutableMapOf<String, MutableSet<ImplementingTypeDefinition<*>>>()
        val implTypeDefs: List<ImplementingTypeDefinition<*>> =
            (
                registry.getTypes(ImplementingTypeDefinition::class.java) +
                    registry.interfaceTypeExtensions().values.flatten() +
                    registry.objectTypeExtensions().values.flatten()
            )
        for (def in implTypeDefs) {
            def.implements.forEach {
                membersMap
                    .getOrPut((it as TypeName).name) {
                        mutableSetOf()
                    }.add(def)
            }
        }

        // Lookup all extension definitions upfront (for Phase 1)
        val enumExtensions = registry.enumTypeExtensions()
        val inputObjectExtensions = registry.inputObjectTypeExtensions()
        val interfaceExtensions = registry.interfaceTypeExtensions()
        val objectExtensions = registry.objectTypeExtensions()
        val scalarExtensions = registry.scalarTypeExtensions()
        val unionExtensions = registry.unionTypeExtensions()

        val schema = SchemaWithData()

        // Phase 1: Create all TypeDef and Directive shells (with def and extensionDefs in data)
        val types = buildMap<String, SchemaWithData.TypeDef>(registry.types().size + registry.scalars().size) {
            registry.types().values.forEach { graphqlDef ->
                val typeDef: SchemaWithData.TypeDef? = when (graphqlDef) {
                    is EnumTypeDefinition -> SchemaWithData.Enum(
                        schema,
                        graphqlDef.name,
                        TypeDefData(graphqlDef, enumExtensions[graphqlDef.name] ?: emptyList())
                    )
                    is InputObjectTypeDefinition -> SchemaWithData.Input(
                        schema,
                        graphqlDef.name,
                        TypeDefData(graphqlDef, inputObjectExtensions[graphqlDef.name] ?: emptyList())
                    )
                    is InterfaceTypeDefinition -> SchemaWithData.Interface(
                        schema,
                        graphqlDef.name,
                        TypeDefData(graphqlDef, interfaceExtensions[graphqlDef.name] ?: emptyList())
                    )
                    is ObjectTypeDefinition ->
                        if (graphqlDef.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL) {
                            SchemaWithData.Object(
                                schema,
                                graphqlDef.name,
                                TypeDefData(graphqlDef, objectExtensions[graphqlDef.name] ?: emptyList())
                            )
                        } else {
                            null
                        }
                    is UnionTypeDefinition -> SchemaWithData.Union(
                        schema,
                        graphqlDef.name,
                        TypeDefData(graphqlDef, unionExtensions[graphqlDef.name] ?: emptyList())
                    )
                    else -> null
                }
                if (typeDef != null) {
                    put(graphqlDef.name, typeDef)
                }
            }
            // registry.scalars gets built-ins as well as explicitly-defined scalars
            registry.scalars().values.forEach {
                val scalarDef = it as ScalarTypeDefinition
                put(
                    scalarDef.name,
                    SchemaWithData.Scalar(
                        schema,
                        scalarDef.name,
                        TypeDefData(scalarDef, scalarExtensions[scalarDef.name] ?: emptyList())
                    )
                )
            }
        }

        val directives = registry.directiveDefinitions.entries.associate {
            it.key to SchemaWithData.Directive(schema, it.value.name, it.value)
        }

        // Phase 2: Populate all TypeDefs and Directives using the decoder
        val decoder = TypeDefinitionRegistryDecoder(registry, types, directives)

        registry.types().values.forEach { def ->
            when (def) {
                is EnumTypeDefinition -> {
                    val enumDef = types[def.name] as SchemaWithData.Enum
                    enumDef.populate(decoder.createEnumExtensions(enumDef))
                }
                is InputObjectTypeDefinition -> {
                    val inputDef = types[def.name] as SchemaWithData.Input
                    inputDef.populate(decoder.createInputExtensions(inputDef))
                }
                is InterfaceTypeDefinition -> {
                    val interfaceDef = types[def.name] as SchemaWithData.Interface
                    val possibleObjectTypes = (membersMap[def.name] ?: emptySet())
                        .filterIsInstance<ObjectTypeDefinition>()
                        .map { types[it.name] as SchemaWithData.Object }
                        .toSet()
                    interfaceDef.populate(decoder.createInterfaceExtensions(interfaceDef), possibleObjectTypes)
                }
                is ObjectTypeDefinition -> {
                    if (def.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL) {
                        val objectDef = types[def.name] as SchemaWithData.Object
                        val unions = (unionsMap[def.name] ?: emptySet()).map { types[it] as SchemaWithData.Union }
                        objectDef.populate(decoder.createObjectExtensions(objectDef), unions)
                    }
                }
                is UnionTypeDefinition -> {
                    val unionDef = types[def.name] as SchemaWithData.Union
                    unionDef.populate(decoder.createUnionExtensions(unionDef))
                }
            }
        }
        registry.scalars().values.forEach { def ->
            val scalarDef = types[def.name] as SchemaWithData.Scalar
            scalarDef.populate(decoder.createScalarExtensions(scalarDef))
        }

        directives.values.forEach { directive ->
            decoder.populate(directive)
        }

        val schemaDef = registry.schemaDefinition().orElse(null)
        val queryTypeDef = rootDef(types, schemaDef, "Query")
        val mutationTypeDef = rootDef(types, schemaDef, "Mutation")
        val subscriptionTypeDef = rootDef(types, schemaDef, "Subscription")

        schema.populate(directives, types, queryTypeDef, mutationTypeDef, subscriptionTypeDef)
        schema
    }

private fun rootDef(
    defs: Map<String, SchemaWithData.TypeDef>,
    schemaDef: SchemaDefinition?,
    stdName: String
): SchemaWithData.Object? {
    // First, check if SchemaDefinition specifies a custom name for this root type
    val nameFromSchema =
        schemaDef
            ?.operationTypeDefinitions
            ?.find { it.name == stdName.lowercase() }
            ?.typeName
            ?.name
    var result: SchemaWithData.TypeDef? = nameFromSchema?.let {
        defs[nameFromSchema] ?: throw IllegalArgumentException("Type not found: $nameFromSchema")
    }
    // Fall back to the standard name (Query, Mutation, Subscription) if not specified
    if (result == null) {
        result = defs[stdName]
    }
    if (result == null) return null
    require(result is SchemaWithData.Object) { "$stdName type is not an object type." }
    return result
}

internal fun <D : ViaductSchema.Directive> Directive.toAppliedDirective(
    def: DirectiveDefinition,
    directiveDef: D,
    typeExprConverter: (Type<*>) -> ViaductSchema.TypeExpr<*>
): ViaductSchema.AppliedDirective<D> {
    val args = def.inputValueDefinitions
    return ViaductSchema.AppliedDirective.of(
        directiveDef,
        args.fold(mutableMapOf<String, ViaductSchema.Literal>()) { m, arg ->
            val t = typeExprConverter(arg.type)
            val v: Value<*>? = this.getArgument(arg.name)?.value ?: arg.defaultValue
            m[arg.name] = if (v != null) {
                ValueConverter.convert(t, v)
            } else {
                if (!t.isNullable) {
                    throw IllegalStateException("No default value for non-nullable argument ${arg.name}")
                }
                ViaductSchema.NULL
            }
            m
        }
    )
}

internal fun <T : ViaductSchema.TypeDef> Type<*>.toTypeExpr(createTypeExpr: (String, Boolean, BitVector) -> ViaductSchema.TypeExpr<T>): ViaductSchema.TypeExpr<T> {
    val listNullable = BitVector.Builder()
    var currentNullableBit = 1L
    var t = this
    while (t !is TypeName) {
        if (t is ListType) {
            listNullable.add(currentNullableBit, 1)
            currentNullableBit = 1L
            t = t.type
        } else if (t is NonNullType) {
            currentNullableBit = 0L
            t = t.type
        } else {
            throw IllegalStateException("Unexpected GraphQL wrapper $this.")
        }
    }
    return createTypeExpr(t.name, (currentNullableBit == 1L), listNullable.build())
}
