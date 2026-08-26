package viaduct.graphql.schema.builder

import viaduct.graphql.schema.SchemaInvariantOptions
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.checkViaductSchemaInvariants
import viaduct.invariants.FailureCollector

/**
 * Builds a [ViaductSchema] from independently constructed definitions.
 *
 * References between definitions use names and are resolved by [build]. A builder may be
 * temporarily inconsistent while it is assembled; [build] resolves references, applies
 * last-update-wins semantics to duplicate additions, checks the resulting schema invariants,
 * and either returns a valid schema or fails.
 */
class ViaductSchemaBuilder(
    var queryTypeName: String? = "Query",
    var mutationTypeName: String? = null,
    var subscriptionTypeName: String? = null,
    val noStandardDefs: Boolean = false,
) {
    private val definitions = mutableListOf<DefinitionBuilder>()
    private var built = false

    init {
        if (!noStandardDefs) {
            standardDefinitions().forEach(::addDefinition)
        }
    }

    /** Adds a type, type extension, or directive definition. */
    fun addDefinition(definition: DefinitionBuilder): ViaductSchemaBuilder =
        apply {
            check(!built) { "This ViaductSchemaBuilder has already been built" }
            definition.claim(this)
            definitions.add(definition)
        }

    /**
     * Resolves all references, checks the resulting schema invariants, and returns an immutable
     * schema.
     */
    fun build(): ViaductSchema {
        check(!built) { "This ViaductSchemaBuilder has already been built" }
        built = true
        val schema =
            ViaductSchemaBuilderDecoder(
                definitions,
                queryTypeName,
                mutationTypeName,
                subscriptionTypeName,
            ).build()
        val check = FailureCollector()
        checkViaductSchemaInvariants(schema, check, SchemaInvariantOptions.ALLOW_EMPTY_TYPES)
        check.assertEmptyMultiline("ViaductSchemaBuilder produced an invalid schema:\n")
        return schema
    }

    companion object {
        /**
         * Returns a builder populated from the parts of [source] accepted by [filter].
         *
         * The returned builder may be temporarily inconsistent. In particular, filtering can
         * remove a definition while retaining references to it or its applications. Add
         * replacement definitions or other updates before calling [build].
         */
        fun filteredCopy(
            source: ViaductSchema,
            filter: ViaductSchemaBuilderFilter,
        ): ViaductSchemaBuilder = ViaductSchemaBuilderFilteredCopy(source, filter).copy()
    }
}

private class ViaductSchemaBuilderDecoder(
    definitions: List<DefinitionBuilder>,
    private val queryTypeName: String?,
    private val mutationTypeName: String?,
    private val subscriptionTypeName: String?,
) {
    private val schema = SchemaWithData()
    private val typeBuilders = linkedMapOf<String, DefinitionBuilder>()
    private val extensionBuilders = linkedMapOf<String, MutableList<DefinitionBuilder>>()
    private val directiveBuilders = linkedMapOf<String, DirectiveBuilder>()

    private lateinit var types: Map<String, SchemaWithData.TypeDef>
    private lateinit var directives: Map<String, SchemaWithData.Directive>

    init {
        definitions.forEach { definition ->
            when (definition) {
                is DirectiveBuilder -> directiveBuilders[definition.name] = definition
                is ScalarTypeBuilder,
                is EnumTypeBuilder,
                is UnionTypeBuilder,
                is InterfaceTypeBuilder,
                is ObjectTypeBuilder,
                is InputObjectTypeBuilder,
                -> typeBuilders[definition.name] = definition
                is ScalarTypeExtensionBuilder,
                is EnumTypeExtensionBuilder,
                is UnionTypeExtensionBuilder,
                is InterfaceTypeExtensionBuilder,
                is ObjectTypeExtensionBuilder,
                is InputObjectTypeExtensionBuilder,
                -> extensionBuilders.getOrPut(definition.name) { mutableListOf() }.add(definition)
            }
        }
        validateExtensions()
    }

    fun build(): ViaductSchema {
        types = typeBuilders.mapValues { (_, builder) -> createTypeShell(builder) }
        directives = directiveBuilders.mapValues { (_, builder) ->
            SchemaWithData.Directive(schema, builder.name, builder.state.buildHolder())
        }

        directiveBuilders.values.forEach(::populateDirective)
        typeBuilders.values.forEach(::populateType)

        schema.populate(
            directives,
            types,
            rootType(queryTypeName, "query"),
            rootType(mutationTypeName, "mutation"),
            rootType(subscriptionTypeName, "subscription"),
        )
        return schema
    }

    private fun createTypeShell(builder: DefinitionBuilder): SchemaWithData.TypeDef =
        when (builder) {
            is ScalarTypeBuilder -> SchemaWithData.Scalar(schema, builder.name, builder.state.buildHolder())
            is EnumTypeBuilder -> SchemaWithData.Enum(schema, builder.name, builder.state.buildHolder())
            is UnionTypeBuilder -> SchemaWithData.Union(schema, builder.name, builder.state.buildHolder())
            is InterfaceTypeBuilder -> SchemaWithData.Interface(schema, builder.name, builder.state.buildHolder())
            is ObjectTypeBuilder -> SchemaWithData.Object(schema, builder.name, builder.state.buildHolder())
            is InputObjectTypeBuilder -> SchemaWithData.Input(schema, builder.name, builder.state.buildHolder())
            else -> error("Unexpected type definition builder ${builder::class.simpleName}")
        }

    private fun populateType(builder: DefinitionBuilder) {
        when (builder) {
            is ScalarTypeBuilder -> populateScalar(builder)
            is EnumTypeBuilder -> populateEnum(builder)
            is UnionTypeBuilder -> populateUnion(builder)
            is InterfaceTypeBuilder -> populateInterface(builder)
            is ObjectTypeBuilder -> populateObject(builder)
            is InputObjectTypeBuilder -> populateInput(builder)
            else -> error("Unexpected type definition builder ${builder::class.simpleName}")
        }
    }

    private fun populateDirective(builder: DirectiveBuilder) {
        val directive = directives.getValue(builder.name)
        val args = lastByName(builder.arguments) { it.name }.map { argument ->
            SchemaWithData.DirectiveArg(
                directive,
                argument.name,
                argument.type.resolve(types),
                buildAppliedDirectives(argument.state.appliedDirectives),
                argument.state.defaultValue != null,
                argument.state.defaultValue,
                argument.state.buildHolder(),
                argument.state.description,
            )
        }
        directive.populate(
            builder.repeatable,
            builder.locations,
            builder.state.sourceLocation,
            args,
            builder.state.description,
        )
    }

    private fun populateScalar(builder: ScalarTypeBuilder) {
        val scalar = types.getValue(builder.name) as SchemaWithData.Scalar
        val extensions =
            buildList {
                add(
                    ViaductSchema.Extension.of<SchemaWithData.Scalar, Nothing>(
                        scalar,
                        { emptyList() },
                        true,
                        buildAppliedDirectives(builder.state.appliedDirectives),
                        builder.state.sourceLocation,
                    )
                )
                extensions<ScalarTypeExtensionBuilder>(builder.name).forEach { extension ->
                    add(
                        ViaductSchema.Extension.of<SchemaWithData.Scalar, Nothing>(
                            scalar,
                            { emptyList() },
                            false,
                            buildAppliedDirectives(extension.state.appliedDirectives),
                            extension.state.sourceLocation,
                        )
                    )
                }
            }
        scalar.populate(extensions, builder.state.description)
    }

    private fun populateEnum(builder: EnumTypeBuilder) {
        val enum = types.getValue(builder.name) as SchemaWithData.Enum
        val extensionBuilders = extensions<EnumTypeExtensionBuilder>(builder.name)
        val values = retainLastByName(listOf(builder.values) + extensionBuilders.map { it.values }) { it.name }
        val extensions =
            buildList {
                add(enumExtension(enum, values[0], true, builder))
                extensionBuilders.forEachIndexed { index, extension ->
                    add(enumExtension(enum, values[index + 1], false, extension))
                }
            }
        enum.populate(extensions, builder.state.description)
    }

    private fun enumExtension(
        enum: SchemaWithData.Enum,
        values: List<EnumValueBuilder>,
        isBase: Boolean,
        builder: DefinitionBuilder,
    ): ViaductSchema.Extension<SchemaWithData.Enum, SchemaWithData.EnumValue> =
        ViaductSchema.Extension.of(
            enum,
            { containingExtension ->
                lastByName(values) { it.name }.map { value ->
                    SchemaWithData.EnumValue(
                        containingExtension,
                        value.name,
                        buildAppliedDirectives(value.state.appliedDirectives),
                        value.state.buildHolder(),
                        value.state.description,
                    )
                }
            },
            isBase,
            buildAppliedDirectives(builder.state.appliedDirectives),
            builder.state.sourceLocation,
        )

    private fun populateUnion(builder: UnionTypeBuilder) {
        val union = types.getValue(builder.name) as SchemaWithData.Union
        val extensionBuilders = extensions<UnionTypeExtensionBuilder>(builder.name)
        val members = retainLastByName(listOf(builder.members) + extensionBuilders.map { it.members }) { it }
        val extensions =
            buildList {
                add(unionExtension(union, members[0], true, builder))
                extensionBuilders.forEachIndexed { index, extension ->
                    add(unionExtension(union, members[index + 1], false, extension))
                }
            }
        union.populate(extensions, builder.state.description)
    }

    private fun unionExtension(
        union: SchemaWithData.Union,
        memberNames: List<String>,
        isBase: Boolean,
        builder: DefinitionBuilder,
    ): ViaductSchema.Extension<SchemaWithData.Union, SchemaWithData.Object> =
        ViaductSchema.Extension.of(
            union,
            {
                lastUnique(memberNames).map { memberName ->
                    requireType<SchemaWithData.Object>(memberName, "Union ${union.name} member")
                }
            },
            isBase,
            buildAppliedDirectives(builder.state.appliedDirectives),
            builder.state.sourceLocation,
        )

    private fun populateInterface(builder: InterfaceTypeBuilder) {
        val interfaceDef = types.getValue(builder.name) as SchemaWithData.Interface
        val extensionBuilders = extensions<InterfaceTypeExtensionBuilder>(builder.name)
        val fields = retainLastByName(listOf(builder.fields) + extensionBuilders.map { it.fields }) { it.name }
        val interfaces = retainLastByName(listOf(builder.interfaces) + extensionBuilders.map { it.interfaces }) { it }
        val extensions =
            buildList {
                add(interfaceExtension(interfaceDef, fields[0], interfaces[0], true, builder))
                extensionBuilders.forEachIndexed { index, extension ->
                    add(
                        interfaceExtension(
                            interfaceDef,
                            fields[index + 1],
                            interfaces[index + 1],
                            false,
                            extension,
                        )
                    )
                }
            }
        val possibleObjects =
            typeBuilders.values
                .filterIsInstance<ObjectTypeBuilder>()
                .filter { objectInterfaces(it).contains(builder.name) }
                .map { types.getValue(it.name) as SchemaWithData.Object }
                .toSet()
        interfaceDef.populate(extensions, possibleObjects, builder.state.description)
    }

    private fun interfaceExtension(
        interfaceDef: SchemaWithData.Interface,
        fields: List<OutputFieldBuilder>,
        interfaceNames: List<String>,
        isBase: Boolean,
        builder: DefinitionBuilder,
    ): ViaductSchema.ExtensionWithSupers<SchemaWithData.Interface, SchemaWithData.Field> =
        ViaductSchema.ExtensionWithSupers.of(
            interfaceDef,
            { containingExtension ->
                lastByName(fields) { it.name }.map { field -> buildOutputField(containingExtension, field) }
            },
            isBase,
            buildAppliedDirectives(builder.state.appliedDirectives),
            lastUnique(interfaceNames).map { requireType<SchemaWithData.Interface>(it, "Implemented type") },
            builder.state.sourceLocation,
        )

    private fun populateObject(builder: ObjectTypeBuilder) {
        val objectDef = types.getValue(builder.name) as SchemaWithData.Object
        val extensionBuilders = extensions<ObjectTypeExtensionBuilder>(builder.name)
        val fields = retainLastByName(listOf(builder.fields) + extensionBuilders.map { it.fields }) { it.name }
        val interfaces = retainLastByName(listOf(builder.interfaces) + extensionBuilders.map { it.interfaces }) { it }
        val extensions =
            buildList {
                add(objectExtension(objectDef, fields[0], interfaces[0], true, builder))
                extensionBuilders.forEachIndexed { index, extension ->
                    add(
                        objectExtension(
                            objectDef,
                            fields[index + 1],
                            interfaces[index + 1],
                            false,
                            extension,
                        )
                    )
                }
            }
        val unions =
            typeBuilders.values
                .filterIsInstance<UnionTypeBuilder>()
                .filter { unionMembers(it).contains(builder.name) }
                .map { types.getValue(it.name) as SchemaWithData.Union }
        objectDef.populate(extensions, unions, builder.state.description)
    }

    private fun objectExtension(
        objectDef: SchemaWithData.Object,
        fields: List<OutputFieldBuilder>,
        interfaceNames: List<String>,
        isBase: Boolean,
        builder: DefinitionBuilder,
    ): ViaductSchema.ExtensionWithSupers<SchemaWithData.Object, SchemaWithData.ObjectField> =
        ViaductSchema.ExtensionWithSupers.of(
            objectDef,
            { containingExtension ->
                lastByName(fields) { it.name }.map { field -> buildObjectField(containingExtension, field) }
            },
            isBase,
            buildAppliedDirectives(builder.state.appliedDirectives),
            lastUnique(interfaceNames).map { requireType<SchemaWithData.Interface>(it, "Implemented type") },
            builder.state.sourceLocation,
        )

    private fun populateInput(builder: InputObjectTypeBuilder) {
        val input = types.getValue(builder.name) as SchemaWithData.Input
        val extensionBuilders = extensions<InputObjectTypeExtensionBuilder>(builder.name)
        val fields = retainLastByName(listOf(builder.fields) + extensionBuilders.map { it.fields }) { it.name }
        val extensions =
            buildList {
                add(inputExtension(input, fields[0], true, builder))
                extensionBuilders.forEachIndexed { index, extension ->
                    add(inputExtension(input, fields[index + 1], false, extension))
                }
            }
        input.populate(extensions, builder.state.description)
    }

    private fun inputExtension(
        input: SchemaWithData.Input,
        fields: List<InputFieldBuilder>,
        isBase: Boolean,
        builder: DefinitionBuilder,
    ): ViaductSchema.Extension<SchemaWithData.Input, SchemaWithData.Field> =
        ViaductSchema.Extension.of(
            input,
            { containingExtension ->
                lastByName(fields) { it.name }.map { field ->
                    SchemaWithData.Field(
                        containingExtension,
                        field.name,
                        field.type.resolve(types),
                        buildAppliedDirectives(field.state.appliedDirectives),
                        field.state.defaultValue != null,
                        field.state.defaultValue,
                        field.state.buildHolder(),
                        field.state.description,
                    )
                }
            },
            isBase,
            buildAppliedDirectives(builder.state.appliedDirectives),
            builder.state.sourceLocation,
        )

    private fun buildOutputField(
        containingExtension: ViaductSchema.Extension<SchemaWithData.Interface, SchemaWithData.Field>,
        builder: OutputFieldBuilder,
    ): SchemaWithData.Field =
        SchemaWithData.Field(
            containingExtension,
            builder.name,
            builder.type.resolve(types),
            buildAppliedDirectives(builder.state.appliedDirectives),
            false,
            null,
            builder.state.buildHolder(),
            builder.state.description,
            { field -> buildFieldArguments(field, builder.arguments) },
        )

    private fun buildObjectField(
        containingExtension: ViaductSchema.ExtensionWithSupers<SchemaWithData.Object, SchemaWithData.ObjectField>,
        builder: OutputFieldBuilder,
    ): SchemaWithData.ObjectField =
        SchemaWithData.ObjectField(
            containingExtension,
            builder.name,
            builder.type.resolve(types),
            buildAppliedDirectives(builder.state.appliedDirectives),
            false,
            null,
            builder.state.buildHolder(),
            builder.state.description,
            { field -> buildFieldArguments(field, builder.arguments) },
        )

    private fun buildFieldArguments(
        field: SchemaWithData.Field,
        builders: List<ArgumentBuilder>,
    ): List<SchemaWithData.FieldArg> =
        lastByName(builders) { it.name }.map { builder ->
            SchemaWithData.FieldArg(
                field,
                builder.name,
                builder.type.resolve(types),
                buildAppliedDirectives(builder.state.appliedDirectives),
                builder.state.defaultValue != null,
                builder.state.defaultValue,
                builder.state.buildHolder(),
                builder.state.description,
            )
        }

    private fun buildAppliedDirectives(builders: List<AppliedDirectiveBuilder>): List<ViaductSchema.AppliedDirective<*>> =
        builders.map { builder ->
            val definitionBuilder = requireNotNull(directiveBuilders[builder.name]) {
                "Directive @${builder.name} is not defined"
            }
            val definition = directives.getValue(builder.name)
            val definitionArguments = lastByName(definitionBuilder.arguments) { it.name }
            val knownArguments = definitionArguments.mapTo(mutableSetOf()) { it.name }
            val unknownArguments = builder.arguments.keys - knownArguments
            require(unknownArguments.isEmpty()) {
                "Directive @${builder.name} has no argument(s) ${unknownArguments.sorted()}"
            }
            val arguments =
                definitionArguments.associate { argument ->
                    argument.name to
                        when {
                            argument.name in builder.arguments -> builder.arguments.getValue(argument.name)
                            argument.state.defaultValue != null -> argument.state.defaultValue!!
                            argument.type.resolve(types).isNullable -> ViaductSchema.NULL
                            else -> error(
                                "No value for required argument '${argument.name}' of directive @${builder.name}"
                            )
                        }
                }
            ViaductSchema.AppliedDirective.of(definition, arguments)
        }

    private fun objectInterfaces(builder: ObjectTypeBuilder): List<String> =
        builder.interfaces +
            extensions<ObjectTypeExtensionBuilder>(builder.name).flatMap { it.interfaces }

    private fun unionMembers(builder: UnionTypeBuilder): List<String> =
        builder.members +
            extensions<UnionTypeExtensionBuilder>(builder.name).flatMap { it.members }

    private fun rootType(
        name: String?,
        operation: String,
    ): SchemaWithData.Object? {
        if (name == null) {
            return null
        }
        val type = requireNotNull(types[name]) {
            "The $operation root type '$name' is not defined"
        }
        require(type is SchemaWithData.Object) {
            "The $operation root type '$name' is not an object type"
        }
        return type
    }

    private inline fun <reified T : SchemaWithData.TypeDef> requireType(
        name: String,
        reference: String,
    ): T {
        val type = requireNotNull(types[name]) {
            "$reference '$name' is not defined"
        }
        require(type is T) {
            "$reference '$name' has type ${type::class.simpleName}, expected ${T::class.simpleName}"
        }
        return type
    }

    private inline fun <reified T : DefinitionBuilder> extensions(name: String): List<T> = extensionBuilders[name].orEmpty().filterIsInstance<T>()

    private fun validateExtensions() {
        extensionBuilders.forEach { (name, extensions) ->
            val base = requireNotNull(typeBuilders[name]) {
                "Type extension '$name' has no base definition"
            }
            extensions.forEach { extension ->
                val matches =
                    when (extension) {
                        is ScalarTypeExtensionBuilder -> base is ScalarTypeBuilder
                        is EnumTypeExtensionBuilder -> base is EnumTypeBuilder
                        is UnionTypeExtensionBuilder -> base is UnionTypeBuilder
                        is InterfaceTypeExtensionBuilder -> base is InterfaceTypeBuilder
                        is ObjectTypeExtensionBuilder -> base is ObjectTypeBuilder
                        is InputObjectTypeExtensionBuilder -> base is InputObjectTypeBuilder
                        else -> false
                    }
                require(matches) {
                    "${extension::class.simpleName}('$name') does not extend ${base::class.simpleName}"
                }
            }
        }
    }
}

private fun <T> lastByName(
    values: List<T>,
    name: (T) -> String,
) = values
    .asReversed()
    .distinctBy(name)
    .asReversed()

private fun <T> retainLastByName(
    groups: List<List<T>>,
    name: (T) -> String,
): List<List<T>> {
    val seen = mutableSetOf<String>()
    return groups
        .asReversed()
        .map { group ->
            group
                .asReversed()
                .filter { seen.add(name(it)) }
                .asReversed()
        }.asReversed()
}

private fun lastUnique(values: List<String>): List<String> =
    values
        .asReversed()
        .distinct()
        .asReversed()

private fun standardDefinitions(): List<DefinitionBuilder> =
    listOf(
        ScalarTypeBuilder("Int"),
        ScalarTypeBuilder("Float"),
        ScalarTypeBuilder("String"),
        ScalarTypeBuilder("Boolean"),
        ScalarTypeBuilder("ID"),
        DirectiveBuilder("include")
            .addArgument(ArgumentBuilder("if", TypeExprBuilder("Boolean", nullable = false)))
            .addLocation(ViaductSchema.Directive.Location.FIELD)
            .addLocation(ViaductSchema.Directive.Location.FRAGMENT_SPREAD)
            .addLocation(ViaductSchema.Directive.Location.INLINE_FRAGMENT),
        DirectiveBuilder("skip")
            .addArgument(ArgumentBuilder("if", TypeExprBuilder("Boolean", nullable = false)))
            .addLocation(ViaductSchema.Directive.Location.FIELD)
            .addLocation(ViaductSchema.Directive.Location.FRAGMENT_SPREAD)
            .addLocation(ViaductSchema.Directive.Location.INLINE_FRAGMENT),
        DirectiveBuilder("deprecated")
            .addArgument(
                ArgumentBuilder("reason", TypeExprBuilder("String", nullable = false))
                    .defaultValue(ViaductSchema.StringLiteral.of("No longer supported"))
            ).addLocation(ViaductSchema.Directive.Location.FIELD_DEFINITION)
            .addLocation(ViaductSchema.Directive.Location.ARGUMENT_DEFINITION)
            .addLocation(ViaductSchema.Directive.Location.INPUT_FIELD_DEFINITION)
            .addLocation(ViaductSchema.Directive.Location.ENUM_VALUE),
        DirectiveBuilder("specifiedBy")
            .addArgument(ArgumentBuilder("url", TypeExprBuilder("String", nullable = false)))
            .addLocation(ViaductSchema.Directive.Location.SCALAR),
        DirectiveBuilder("oneOf")
            .description("Indicates an Input Object is a OneOf Input Object.")
            .addLocation(ViaductSchema.Directive.Location.INPUT_OBJECT),
    )
