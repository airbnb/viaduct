package viaduct.graphql.schema.graphqljava

import graphql.Directives
import graphql.GraphQLContext
import graphql.Scalars
import graphql.execution.CoercedVariables
import graphql.introspection.Introspection
import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumTypeDefinition
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.InputObjectTypeDefinition
import graphql.language.IntValue
import graphql.language.InterfaceTypeDefinition
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectValue
import graphql.language.ScalarTypeDefinition
import graphql.language.SourceLocation
import graphql.language.StringValue
import graphql.language.UnionTypeDefinition
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLAppliedDirectiveArgument
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLUnionType
import viaduct.graphql.schema.ViaductSchema

/**
 * A passthrough Coercing implementation for custom scalars that just returns values as-is.
 * This is suitable for creating an unexecutable schema representation.
 */
private object PassthroughCoercing : Coercing<Any, Any> {
    override fun serialize(dataFetcherResult: Any): Any = dataFetcherResult

    override fun parseValue(input: Any): Any = input

    override fun parseLiteral(input: Any): Any = input

    override fun parseLiteral(
        input: Value<*>,
        variables: CoercedVariables,
        graphQLContext: GraphQLContext,
        locale: java.util.Locale
    ): Any? {
        return when (input) {
            is StringValue -> input.value
            is IntValue -> input.value
            is FloatValue -> input.value
            is BooleanValue -> input.isValue
            is NullValue -> null
            is ArrayValue -> input.values.map { parseLiteral(it, variables, graphQLContext, locale) }
            is ObjectValue -> input.objectFields.associate { it.name to parseLiteral(it.value, variables, graphQLContext, locale) }
            else -> input
        }
    }
}

fun ViaductSchema.toGraphQLSchema(
    scalarsNeeded: Set<String> = emptySet(),
    additionalScalars: Set<String> = emptySet(),
): GraphQLSchema = GraphQLJavaSchemaBuilder(this, scalarsNeeded, additionalScalars).build()

private class GraphQLJavaSchemaBuilder(
    private val inputSchema: ViaductSchema,
    private val scalarsNeeded: Set<String>,
    private val additionalScalars: Set<String>,
) {
    private val sbuilder = GraphQLSchema.Builder()
    private val registryBuilder = GraphQLCodeRegistry.newCodeRegistry()
    private val convertedTypeDefs = mutableMapOf<String, GraphQLNamedType>()
    private val scalarsSeen = mutableSetOf<String>()

    fun build(): GraphQLSchema {
        // Add built-in scalars first - needed for directive argument types
        sbuilder.additionalType(Scalars.GraphQLString)
        sbuilder.additionalType(Scalars.GraphQLBoolean)

        // Add directive definitions BEFORE types (types may have applied directives)
        inputSchema.directives.values.forEach {
            sbuilder.additionalDirective(convertDirective(it))
        }

        // Add built-in directives if not already present
        if (!inputSchema.directives.containsKey("deprecated")) {
            sbuilder.additionalDirective(Directives.DeprecatedDirective)
        }
        if (!inputSchema.directives.containsKey("specifiedBy")) {
            sbuilder.additionalDirective(Directives.SpecifiedByDirective)
        }

        // Add additional scalars
        additionalScalars.forEach {
            sbuilder.additionalType(
                GraphQLScalarType.Builder()
                    .name(it)
                    .coercing(PassthroughCoercing)
                    .build()
            )
        }

        // Add types
        inputSchema.types.values.forEach {
            if (firstPassType(it)) {
                when (it) {
                    is ViaductSchema.Enum -> sbuilder.additionalType(convertEnumType(it))
                    is ViaductSchema.Input -> sbuilder.additionalType(convertInputObjectType(it))
                    is ViaductSchema.Interface -> sbuilder.additionalType(convertInterfaceType(it))
                    is ViaductSchema.Object -> sbuilder.additionalType(convertObjectType(it))
                    is ViaductSchema.Union -> sbuilder.additionalType(convertUnionType(it))
                }
            }
        }

        inputSchema.queryTypeDef?.let { sbuilder.query(convertObjectType(it)) }
        inputSchema.mutationTypeDef?.let { sbuilder.mutation(convertObjectType(it)) }
        inputSchema.subscriptionTypeDef?.let { sbuilder.subscription(convertObjectType(it)) }

        // For built-in scalars an exception is thrown if you do them first, so do them last
        inputSchema.types.values.filter { it is ViaductSchema.Scalar }.forEach {
            if (scalarsNeeded.contains(it.name)) {
                sbuilder.additionalType(convertScalarType(it as ViaductSchema.Scalar))
            }
        }

        sbuilder.codeRegistry(registryBuilder.build())

        return sbuilder.build()
    }

    // everything in this section is internal for testing

    internal fun typeRef(typeDef: ViaductSchema.TypeDef): GraphQLNamedType = convertedTypeDefs[typeDef.name] ?: GraphQLTypeReference(typeDef.name)

    /** Note: updates [scalarsSeen]. */
    internal fun typeExpr(source: ViaductSchema.TypeExpr): GraphQLType {
        val base = source.baseTypeDef
        if (base is ViaductSchema.Scalar) scalarsSeen.add(base.name)
        var result: GraphQLType = typeRef(base)
        if (!source.baseTypeNullable) result = GraphQLNonNull(result)
        for (d in source.listDepth - 1 downTo 0) {
            result = GraphQLList(result)
            if (!source.nullableAtDepth(d)) result = GraphQLNonNull(result)
        }
        return result
    }

    internal fun convertEnumType(typeDef: ViaductSchema.Enum): GraphQLEnumType {
        val c = convertedTypeDefs[typeDef.name]
        if (c != null) return c as GraphQLEnumType

        val sourceLocation = convertSourceLocation(typeDef.sourceLocation)
        val result = GraphQLEnumType.Builder()
            .name(typeDef.name)
            .apply {
                typeDef.values.forEach { value(convertEnumValue(it)) }
                // Apply type-level directives
                typeDef.appliedDirectives.forEach {
                    withAppliedDirective(convertAppliedDirective(it))
                }
                // Set definition with source location
                if (sourceLocation != null) {
                    definition(
                        EnumTypeDefinition.newEnumTypeDefinition()
                            .name(typeDef.name)
                            .sourceLocation(sourceLocation)
                            .build()
                    )
                }
            }
            .build()
        convertedTypeDefs[typeDef.name] = result
        return result
    }

    internal fun convertEnumValue(source: ViaductSchema.EnumValue): GraphQLEnumValueDefinition {
        val deprecationInfo = extractDeprecation(source.appliedDirectives)
        return GraphQLEnumValueDefinition.Builder()
            .name(source.name)
            .value(source.name)
            .apply {
                if (deprecationInfo != null) {
                    deprecationReason(deprecationInfo)
                }
                // Apply all directives including @deprecated
                source.appliedDirectives.forEach {
                    withAppliedDirective(convertAppliedDirective(it))
                }
            }
            .build()
    }

    internal fun convertScalarType(typeDef: ViaductSchema.Scalar): GraphQLScalarType {
        // Use built-in scalars when available, as they have proper Coercing implementations
        return when (typeDef.name) {
            "Int" -> Scalars.GraphQLInt
            "Float" -> Scalars.GraphQLFloat
            "String" -> Scalars.GraphQLString
            "Boolean" -> Scalars.GraphQLBoolean
            "ID" -> Scalars.GraphQLID
            else -> {
                val sourceLocation = convertSourceLocation(typeDef.sourceLocation)
                GraphQLScalarType.Builder()
                    .name(typeDef.name)
                    .coercing(PassthroughCoercing)
                    .apply {
                        if (sourceLocation != null) {
                            definition(
                                ScalarTypeDefinition.newScalarTypeDefinition()
                                    .name(typeDef.name)
                                    .sourceLocation(sourceLocation)
                                    .build()
                            )
                        }
                    }
                    .build()
            }
        }
    }

    internal fun inputTypeExpr(source: ViaductSchema.TypeExpr): GraphQLInputType = typeExpr(source) as GraphQLInputType

    internal fun outputTypeExpr(source: ViaductSchema.TypeExpr): GraphQLOutputType = typeExpr(source) as GraphQLOutputType

    internal fun convertInputObjectType(source: ViaductSchema.Input): GraphQLInputObjectType {
        val c = convertedTypeDefs[source.name]
        if (c != null) return c as GraphQLInputObjectType

        val tbuilder = GraphQLInputObjectType.Builder()
        val sourceLocation = convertSourceLocation(source.sourceLocation)

        tbuilder.name(source.name)

        source.fields.forEach {
            tbuilder.field(convertInputObjectField(it))
        }

        // Apply type-level directives
        source.appliedDirectives.forEach {
            tbuilder.withAppliedDirective(convertAppliedDirective(it))
        }

        // Set definition with source location
        if (sourceLocation != null) {
            tbuilder.definition(
                InputObjectTypeDefinition.newInputObjectDefinition()
                    .name(source.name)
                    .sourceLocation(sourceLocation)
                    .build()
            )
        }

        val result = tbuilder.build()
        convertedTypeDefs[source.name] = result
        return result
    }

    internal fun convertInputObjectField(source: ViaductSchema.Field): GraphQLInputObjectField {
        val deprecationInfo = extractDeprecation(source.appliedDirectives)
        val fbuilder = GraphQLInputObjectField.Builder()
        fbuilder.name(source.name)
        fbuilder.type(inputTypeExpr(source.type))
        if (source.hasDefault) {
            fbuilder.defaultValueLiteral(toGraphQLValue(source.defaultValue))
        }
        if (deprecationInfo != null) {
            fbuilder.deprecate(deprecationInfo)
        }
        // Apply all directives including @deprecated
        source.appliedDirectives.forEach {
            fbuilder.withAppliedDirective(convertAppliedDirective(it))
        }
        return fbuilder.build()
    }

    internal fun convertObjectType(source: ViaductSchema.Object): GraphQLObjectType {
        val c = convertedTypeDefs[source.name]
        if (c != null) return c as GraphQLObjectType

        val tbuilder = GraphQLObjectType.Builder()
        val sourceLocation = convertSourceLocation(source.sourceLocation)

        tbuilder.name(source.name)

        source.supers.forEach {
            val superType = typeRef(it)
            when (superType) {
                is GraphQLInterfaceType -> tbuilder.withInterface(superType)
                is GraphQLTypeReference -> tbuilder.withInterface(superType)
                else -> throw IllegalStateException("Unexpected supertype $superType")
            }
        }

        source.fields.forEach {
            tbuilder.field(convertObjectTypeField(it))
        }

        // Apply type-level directives
        source.appliedDirectives.forEach {
            tbuilder.withAppliedDirective(convertAppliedDirective(it))
        }

        // Set definition with source location
        if (sourceLocation != null) {
            tbuilder.definition(
                ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(source.name)
                    .sourceLocation(sourceLocation)
                    .build()
            )
        }

        val result = tbuilder.build()
        convertedTypeDefs[source.name] = result
        return result
    }

    internal fun convertInterfaceType(source: ViaductSchema.Interface): GraphQLInterfaceType {
        val c = convertedTypeDefs[source.name]
        if (c != null) return c as GraphQLInterfaceType

        val tbuilder = GraphQLInterfaceType.Builder()
        val sourceLocation = convertSourceLocation(source.sourceLocation)

        tbuilder.name(source.name)

        source.supers.forEach {
            val superType = typeRef(it)
            when (superType) {
                is GraphQLInterfaceType -> tbuilder.withInterface(superType)
                is GraphQLTypeReference -> tbuilder.withInterface(superType)
                else -> throw IllegalStateException("Unexpected supertype $superType")
            }
        }

        source.fields.forEach {
            tbuilder.field(convertObjectTypeField(it))
        }

        // Apply type-level directives
        source.appliedDirectives.forEach {
            tbuilder.withAppliedDirective(convertAppliedDirective(it))
        }

        // Set definition with source location
        if (sourceLocation != null) {
            tbuilder.definition(
                InterfaceTypeDefinition.newInterfaceTypeDefinition()
                    .name(source.name)
                    .sourceLocation(sourceLocation)
                    .build()
            )
        }

        val result = tbuilder.build()
        convertedTypeDefs[source.name] = result
        registryBuilder.typeResolverIfAbsent(result) { env ->
            error("No type resolver configured for interface '${source.name}'. This schema is not executable.")
        }
        return result
    }

    internal fun convertObjectTypeField(source: ViaductSchema.Field): GraphQLFieldDefinition {
        val deprecationInfo = extractDeprecation(source.appliedDirectives)
        return GraphQLFieldDefinition.Builder()
            .name(source.name)
            .type(outputTypeExpr(source.type))
            .apply {
                source.args.forEach { argument(convertArg(it)) }
                if (deprecationInfo != null) {
                    deprecate(deprecationInfo)
                }
                // Apply all directives including @deprecated
                source.appliedDirectives.forEach {
                    withAppliedDirective(convertAppliedDirective(it))
                }
            }
            .build()
    }

    internal fun convertArg(source: ViaductSchema.Arg): GraphQLArgument {
        val deprecationInfo = extractDeprecation(source.appliedDirectives)
        return GraphQLArgument.Builder()
            .name(source.name)
            .type(inputTypeExpr(source.type as ViaductSchema.TypeExpr))
            .apply {
                if (source.hasDefault) {
                    defaultValueLiteral(toGraphQLValue(source.defaultValue))
                }
                if (deprecationInfo != null) {
                    deprecate(deprecationInfo)
                }
                // Apply all directives including @deprecated
                source.appliedDirectives.forEach {
                    withAppliedDirective(convertAppliedDirective(it))
                }
            }
            .build()
    }

    internal fun convertUnionType(typeDef: ViaductSchema.Union): GraphQLUnionType {
        val c = convertedTypeDefs[typeDef.name]
        if (c != null) return c as GraphQLUnionType

        val sourceLocation = convertSourceLocation(typeDef.sourceLocation)
        val result = GraphQLUnionType.Builder()
            .name(typeDef.name)
            .apply {
                typeDef.possibleObjectTypes.forEach { possibleType(GraphQLTypeReference(it.name)) }
                // Apply type-level directives
                typeDef.appliedDirectives.forEach {
                    withAppliedDirective(convertAppliedDirective(it))
                }
                // Set definition with source location
                if (sourceLocation != null) {
                    definition(
                        UnionTypeDefinition.newUnionTypeDefinition()
                            .name(typeDef.name)
                            .sourceLocation(sourceLocation)
                            .build()
                    )
                }
            }
            .build()
        convertedTypeDefs[typeDef.name] = result
        registryBuilder.typeResolverIfAbsent(result) { env ->
            error("No type resolver configured for union '${typeDef.name}'. This schema is not executable.")
        }
        return result
    }

    /**
     * Extracts the deprecation reason from applied directives.
     * Returns the reason string if @deprecated is present, null otherwise.
     */
    private fun extractDeprecation(directives: Collection<ViaductSchema.AppliedDirective>): String? {
        val deprecated = directives.find { it.name == "deprecated" } ?: return null
        val reason = deprecated.arguments["reason"]
        return when (reason) {
            is StringValue -> reason.value
            is String -> reason
            null -> "No longer supported" // GraphQL spec default
            else -> reason.toString()
        }
    }

    /**
     * Converts a ViaductSchema.AppliedDirective to a graphql-java GraphQLAppliedDirective.
     *
     * Note: The argument values in ViaductSchema.AppliedDirective.arguments are stored as
     * graphql.language.Value<*> objects when using ValueConverter.default.
     */
    internal fun convertAppliedDirective(source: ViaductSchema.AppliedDirective): GraphQLAppliedDirective {
        // Look up the directive definition to get argument types
        val directiveDef = inputSchema.directives[source.name]

        return GraphQLAppliedDirective.newDirective()
            .name(source.name)
            .apply {
                source.arguments.forEach { (name, value) ->
                    // Convert the value to a graphql.language.Value if it isn't already
                    val literalValue = toGraphQLValue(value)

                    // Get the argument type from the directive definition, or use String as fallback
                    val argType = directiveDef?.args?.find { it.name == name }?.type?.let {
                        inputTypeExpr(it as ViaductSchema.TypeExpr)
                    } ?: Scalars.GraphQLString

                    argument(
                        GraphQLAppliedDirectiveArgument.newArgument()
                            .name(name)
                            .type(argType)
                            .valueLiteral(literalValue)
                            .build()
                    )
                }
            }
            .build()
    }

    /**
     * Converts a ViaductSchema.Directive to a graphql-java GraphQLDirective.
     */
    internal fun convertDirective(source: ViaductSchema.Directive): GraphQLDirective {
        return GraphQLDirective.newDirective()
            .name(source.name)
            .repeatable(source.isRepeatable)
            .apply {
                source.args.forEach { argument(convertDirectiveArg(it)) }
                source.allowedLocations.forEach {
                    validLocation(convertDirectiveLocation(it))
                }
            }
            .build()
    }

    /**
     * Converts a ViaductSchema.DirectiveArg to a graphql-java GraphQLArgument.
     */
    private fun convertDirectiveArg(source: ViaductSchema.DirectiveArg): GraphQLArgument {
        return GraphQLArgument.Builder()
            .name(source.name)
            .type(inputTypeExpr(source.type as ViaductSchema.TypeExpr))
            .apply {
                if (source.hasDefault) {
                    defaultValueLiteral(toGraphQLValue(source.defaultValue))
                }
            }
            .build()
    }

    /**
     * Converts a ViaductSchema.Directive.Location to graphql-java Introspection.DirectiveLocation.
     */
    private fun convertDirectiveLocation(location: ViaductSchema.Directive.Location): Introspection.DirectiveLocation {
        return when (location) {
            ViaductSchema.Directive.Location.QUERY -> Introspection.DirectiveLocation.QUERY
            ViaductSchema.Directive.Location.MUTATION -> Introspection.DirectiveLocation.MUTATION
            ViaductSchema.Directive.Location.SUBSCRIPTION -> Introspection.DirectiveLocation.SUBSCRIPTION
            ViaductSchema.Directive.Location.FIELD -> Introspection.DirectiveLocation.FIELD
            ViaductSchema.Directive.Location.FRAGMENT_DEFINITION -> Introspection.DirectiveLocation.FRAGMENT_DEFINITION
            ViaductSchema.Directive.Location.FRAGMENT_SPREAD -> Introspection.DirectiveLocation.FRAGMENT_SPREAD
            ViaductSchema.Directive.Location.INLINE_FRAGMENT -> Introspection.DirectiveLocation.INLINE_FRAGMENT
            ViaductSchema.Directive.Location.VARIABLE_DEFINITION -> Introspection.DirectiveLocation.VARIABLE_DEFINITION
            ViaductSchema.Directive.Location.SCHEMA -> Introspection.DirectiveLocation.SCHEMA
            ViaductSchema.Directive.Location.SCALAR -> Introspection.DirectiveLocation.SCALAR
            ViaductSchema.Directive.Location.OBJECT -> Introspection.DirectiveLocation.OBJECT
            ViaductSchema.Directive.Location.FIELD_DEFINITION -> Introspection.DirectiveLocation.FIELD_DEFINITION
            ViaductSchema.Directive.Location.ARGUMENT_DEFINITION -> Introspection.DirectiveLocation.ARGUMENT_DEFINITION
            ViaductSchema.Directive.Location.INTERFACE -> Introspection.DirectiveLocation.INTERFACE
            ViaductSchema.Directive.Location.UNION -> Introspection.DirectiveLocation.UNION
            ViaductSchema.Directive.Location.ENUM -> Introspection.DirectiveLocation.ENUM
            ViaductSchema.Directive.Location.ENUM_VALUE -> Introspection.DirectiveLocation.ENUM_VALUE
            ViaductSchema.Directive.Location.INPUT_OBJECT -> Introspection.DirectiveLocation.INPUT_OBJECT
            ViaductSchema.Directive.Location.INPUT_FIELD_DEFINITION -> Introspection.DirectiveLocation.INPUT_FIELD_DEFINITION
        }
    }

    /**
     * Converts a ViaductSchema.SourceLocation to a graphql-java SourceLocation.
     * Line and column are set to -1 since we don't track those in ViaductSchema.
     */
    private fun convertSourceLocation(source: ViaductSchema.SourceLocation?): SourceLocation? {
        return source?.let { SourceLocation(-1, -1, it.sourceName) }
    }

    companion object {
        private fun firstPassType(t: ViaductSchema.TypeDef) = !(t.name.startsWith("__") || t.name == "Query" || t.name == "Mutation" || t.name == "Subscription")
    }
}

/**
 * Converts a value from ViaductSchema representation back to graphql-java Value<*>.
 *
 * Values must already be graphql.language.Value<*> types, not Kotlin primitives.
 * The only exceptions are:
 * - null → NullValue
 * - List<*> → ArrayValue (elements recursively converted)
 * - Map<String, *> → ObjectValue (values recursively converted)
 */
@Suppress("UNCHECKED_CAST")
private fun toGraphQLValue(value: Any?): Value<*> {
    return when (value) {
        // Already a Value - just return it
        is Value<*> -> value

        // Null
        null -> NullValue.of()

        // List
        is List<*> -> ArrayValue.newArrayValue()
            .values(value.map { toGraphQLValue(it) })
            .build()

        // Map (for input objects)
        is Map<*, *> -> ObjectValue.newObjectValue()
            .objectFields(
                (value as Map<String, *>).map { (k, v) ->
                    ObjectField.newObjectField()
                        .name(k)
                        .value(toGraphQLValue(v))
                        .build()
                }
            )
            .build()

        else -> throw IllegalArgumentException(
            "Unsupported value type for conversion: ${value::class}. " +
                "Values must already be graphql.language.Value types, not Kotlin primitives."
        )
    }
}
