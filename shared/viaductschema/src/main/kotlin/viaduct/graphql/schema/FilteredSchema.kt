package viaduct.graphql.schema

import viaduct.invariants.FailureCollector

/**
 * Creates a filtered schema from the given schema entries.
 *
 * See KDoc for [ViaductSchema] for background.
 *
 * The `xyzTypeNameFromBaseSchema` parameters here work a bit differently from
 * their analogs in `GJSchemaRaw`. In `GJSchemaRaw`, they are used to set
 * the root types, and thus if they name a non-existent type, the right behavior
 * is to fail. Here, the base schema is assumed to have (or not have) a root type
 * defs, and the `xyzTypeNameFromBaseSchema` is intended to pass the name of those
 * types in. Now, it might be the case that those types get filtered out, so
 * it's _not_ an error if they name non-existent types. The code can't actually
 * know, however, if `xyzTypeNameFromBaseSchema` is actually from the base schema,
 * so it _does_ check to ensure that it names an object type.
 *
 * @return A [SchemaWithData] where each node's [SchemaWithData.Def.data] property
 *         holds the corresponding unfiltered [ViaductSchema.Def]. Use extension
 *         properties like [unfilteredDef] to access them.
 */
internal fun <T : ViaductSchema.TypeDef> filteredSchema(
    filter: SchemaFilter,
    schemaEntries: Iterable<Map.Entry<String, T>>,
    directiveEntries: Iterable<Map.Entry<String, ViaductSchema.Directive>>,
    schemaInvariantOptions: SchemaInvariantOptions,
    queryTypeNameFromBaseSchema: String?,
    mutationTypeNameFromBaseSchema: String?,
    subscriptionTypeNameFromBaseSchema: String?
): SchemaWithData {
    val schema = SchemaWithData()

    // Phase 1: Create all TypeDef shells (no filter or defs passed to constructors)
    val defs = buildMap {
        schemaEntries
            .filter { (_, value) -> filter.includeTypeDef(value) }
            .forEach { (k, v) ->
                val shell: SchemaWithData.TypeDef = when (v) {
                    is ViaductSchema.Enum -> SchemaWithData.Enum(schema, v.name, v)
                    is ViaductSchema.Input -> SchemaWithData.Input(schema, v.name, v)
                    is ViaductSchema.Interface -> SchemaWithData.Interface(schema, v.name, v)
                    is ViaductSchema.Object -> SchemaWithData.Object(schema, v.name, v)
                    is ViaductSchema.Union -> SchemaWithData.Union(schema, v.name, v)
                    is ViaductSchema.Scalar -> SchemaWithData.Scalar(schema, v.name, v)
                    else -> throw IllegalArgumentException("Unexpected type definition $v")
                }
                put(k, shell)
            }
    }

    // Create directive shells
    val directives = directiveEntries.associate { (k, v) ->
        k to SchemaWithData.Directive(schema, v.name, v)
    }

    // Phase 2: Create decoder and populate all types and directives
    val decoder = FilteredSchemaDecoder(filter, defs, directives)

    for (typeDef in defs.values) {
        when (typeDef) {
            is SchemaWithData.Scalar -> typeDef.populate(
                decoder.createScalarExtensions(typeDef)
            )
            is SchemaWithData.Enum -> typeDef.populate(
                decoder.createEnumExtensions(typeDef)
            )
            is SchemaWithData.Input -> typeDef.populate(
                decoder.createInputExtensions(typeDef)
            )
            is SchemaWithData.Union -> typeDef.populate(
                decoder.createUnionExtensions(typeDef)
            )
            is SchemaWithData.Interface -> {
                val unfilteredDef = typeDef.unfilteredDef
                val filteredSupers = decoder.computeFilteredSupers(unfilteredDef)
                typeDef.populate(
                    decoder.createInterfaceExtensions(typeDef, filteredSupers),
                    decoder.computePossibleObjectTypes(typeDef)
                )
            }
            is SchemaWithData.Object -> {
                val unfilteredDef = typeDef.unfilteredDef
                val filteredSupers = decoder.computeFilteredSupers(unfilteredDef)
                typeDef.populate(
                    decoder.createObjectExtensions(typeDef, filteredSupers),
                    decoder.computeFilteredUnions(typeDef)
                )
            }
        }
    }

    for (directive in directives.values) {
        decoder.populate(directive)
    }

    // Determine root types
    fun rootDef(nameFromBaseSchema: String?): SchemaWithData.Object? {
        val result = nameFromBaseSchema?.let { defs[it] }
        if (result != null && result !is SchemaWithData.Object) {
            throw IllegalArgumentException("$result is not an object type.")
        }
        return result as? SchemaWithData.Object
    }

    // Populate schema
    schema.populate(
        directives,
        defs,
        rootDef(queryTypeNameFromBaseSchema),
        rootDef(mutationTypeNameFromBaseSchema),
        rootDef(subscriptionTypeNameFromBaseSchema)
    )

    val violations = FailureCollector()
    checkViaductSchemaInvariants(schema, violations, schemaInvariantOptions)
    violations.assertEmptyMultiline("FilteredSchema failed the following invariant checks:\n")

    return schema
}

/**
 * Transforms unfiltered ViaductSchema elements into filtered SchemaWithData elements.
 * This class centralizes all filtering and transformation logic.
 */
internal class FilteredSchemaDecoder(
    private val filter: SchemaFilter,
    private val filteredTypes: Map<String, SchemaWithData.TypeDef>,
    private val filteredDirectives: Map<String, SchemaWithData.Directive>,
) {
    // ========== Core: Type Resolution ==========

    fun getFilteredType(name: String): SchemaWithData.TypeDef? = filteredTypes[name]

    /**
     * Remap applied directives to use the filtered schema's directive definitions.
     */
    private fun remapAppliedDirectives(unfilteredAppliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>): List<ViaductSchema.AppliedDirective<*>> =
        unfilteredAppliedDirectives.map { ad ->
            val filteredDirective = filteredDirectives[ad.name]
                ?: error("Directive @${ad.name} not found in filtered directives map.")
            ViaductSchema.AppliedDirective.of(filteredDirective, ad.arguments)
        }

    // ========== Scalar ==========

    fun createScalarExtensions(scalar: SchemaWithData.Scalar): List<ViaductSchema.Extension<SchemaWithData.Scalar, Nothing>> {
        val unfilteredDef = scalar.unfilteredDef
        return unfilteredDef.extensions.map { unfilteredExt ->
            ViaductSchema.Extension.of(
                def = scalar,
                memberFactory = { emptyList() },
                isBase = unfilteredExt.isBase,
                appliedDirectives = remapAppliedDirectives(unfilteredExt.appliedDirectives),
                sourceLocation = unfilteredExt.sourceLocation
            )
        }
    }

    // ========== Enum ==========

    fun createEnumExtensions(enumDef: SchemaWithData.Enum): List<ViaductSchema.Extension<SchemaWithData.Enum, SchemaWithData.EnumValue>> {
        val unfilteredDef = enumDef.unfilteredDef
        return unfilteredDef.extensions.map { unfilteredExt ->
            ViaductSchema.Extension.of(
                def = enumDef,
                memberFactory = { ext ->
                    unfilteredExt.members
                        .filter(filter::includeEnumValue)
                        .map { SchemaWithData.EnumValue(ext, it.name, remapAppliedDirectives(it.appliedDirectives), it) }
                },
                isBase = unfilteredExt == unfilteredDef.extensions.first(),
                appliedDirectives = remapAppliedDirectives(unfilteredExt.appliedDirectives),
                sourceLocation = unfilteredExt.sourceLocation
            )
        }
    }

    // ========== Input ==========

    fun createInputExtensions(inputDef: SchemaWithData.Input): List<ViaductSchema.Extension<SchemaWithData.Input, SchemaWithData.Field>> {
        val unfilteredDef = inputDef.unfilteredDef
        return unfilteredDef.extensions.map { unfilteredExt ->
            ViaductSchema.Extension.of(
                def = inputDef,
                memberFactory = { ext ->
                    unfilteredExt.members
                        .filter { filter.includeField(it) && filter.includeTypeDef(it.type.baseTypeDef) }
                        .map { createField(it, ext) }
                },
                isBase = unfilteredExt == unfilteredDef.extensions.first(),
                appliedDirectives = remapAppliedDirectives(unfilteredExt.appliedDirectives),
                sourceLocation = unfilteredExt.sourceLocation
            )
        }
    }

    // ========== Union ==========

    fun createUnionExtensions(unionDef: SchemaWithData.Union): List<ViaductSchema.Extension<SchemaWithData.Union, SchemaWithData.Object>> {
        val unfilteredDef = unionDef.unfilteredDef
        return unfilteredDef.extensions.map { unfilteredExt ->
            ViaductSchema.Extension.of(
                def = unionDef,
                memberFactory = {
                    unfilteredExt.members
                        .filter(filter::includeTypeDef)
                        .map { filteredTypes[it.name] as SchemaWithData.Object }
                },
                isBase = unfilteredExt == unfilteredDef.extensions.first(),
                appliedDirectives = remapAppliedDirectives(unfilteredExt.appliedDirectives),
                sourceLocation = unfilteredExt.sourceLocation
            )
        }
    }

    // ========== Interface ==========

    fun createInterfaceExtensions(
        interfaceDef: SchemaWithData.Interface,
        filteredSupers: List<SchemaWithData.Interface>
    ): List<ViaductSchema.ExtensionWithSupers<SchemaWithData.Interface, SchemaWithData.Field>> {
        val unfilteredDef = interfaceDef.unfilteredDef
        val superNames = filteredSupers.map { it.name }.toSet()
        return unfilteredDef.extensions.map { unfilteredExt ->
            val newSupers = unfilteredExt.supers
                .filter { superNames.contains(it.name) }
                .map { filteredTypes[it.name] as SchemaWithData.Interface }
            ViaductSchema.ExtensionWithSupers.of(
                def = interfaceDef,
                memberFactory = { ext ->
                    unfilteredExt.members
                        .filter { filter.includeField(it) && filter.includeTypeDef(it.type.baseTypeDef) }
                        .map { createField(it, ext) }
                },
                isBase = unfilteredExt == unfilteredDef.extensions.first(),
                appliedDirectives = remapAppliedDirectives(unfilteredExt.appliedDirectives),
                sourceLocation = unfilteredExt.sourceLocation,
                supers = newSupers
            )
        }
    }

    fun computeFilteredSupers(unfilteredDef: ViaductSchema.OutputRecord): List<SchemaWithData.Interface> =
        unfilteredDef.supers
            .filter { filter.includeSuper(unfilteredDef, it) && filter.includeTypeDef(it) }
            .map { filteredTypes[it.name] as SchemaWithData.Interface }

    fun computePossibleObjectTypes(interfaceDef: SchemaWithData.Interface): Set<SchemaWithData.Object> {
        val unfilteredDef = interfaceDef.unfilteredDef
        return unfilteredDef.possibleObjectTypes
            .filter { includePossibleSubType(it, unfilteredDef) }
            .map { filteredTypes[it.name] as SchemaWithData.Object }
            .toSet()
    }

    private fun includePossibleSubType(
        possibleSubType: ViaductSchema.OutputRecord,
        targetSuperType: ViaductSchema.Interface
    ): Boolean =
        when {
            possibleSubType.supers.contains(targetSuperType) -> filter.includeSuper(possibleSubType, targetSuperType)
            else ->
                possibleSubType.supers.any {
                    filter.includeSuper(possibleSubType, it) && includePossibleSubType(it, targetSuperType)
                }
        }

    // ========== Object ==========

    fun createObjectExtensions(
        objectDef: SchemaWithData.Object,
        filteredSupers: List<SchemaWithData.Interface>
    ): List<ViaductSchema.ExtensionWithSupers<SchemaWithData.Object, SchemaWithData.Field>> {
        val unfilteredDef = objectDef.unfilteredDef
        val superNames = filteredSupers.map { it.name }.toSet()
        return unfilteredDef.extensions.map { unfilteredExt ->
            val newSupers = unfilteredExt.supers
                .filter { superNames.contains(it.name) }
                .map { filteredTypes[it.name] as SchemaWithData.Interface }
            ViaductSchema.ExtensionWithSupers.of(
                def = objectDef,
                memberFactory = { ext ->
                    unfilteredExt.members
                        .filter { filter.includeField(it) && filter.includeTypeDef(it.type.baseTypeDef) }
                        .map { createField(it, ext) }
                },
                isBase = unfilteredExt == unfilteredDef.extensions.first(),
                appliedDirectives = remapAppliedDirectives(unfilteredExt.appliedDirectives),
                sourceLocation = unfilteredExt.sourceLocation,
                supers = newSupers
            )
        }
    }

    fun computeFilteredUnions(objectDef: SchemaWithData.Object): List<SchemaWithData.Union> {
        val unfilteredDef = objectDef.unfilteredDef
        return unfilteredDef.unions
            .filter { filter.includeTypeDef(it) }
            .map { filteredTypes[it.name] as SchemaWithData.Union }
    }

    // ========== Directive ==========

    fun populate(directive: SchemaWithData.Directive) {
        val unfilteredDef = directive.unfilteredDef
        val args = unfilteredDef.args.map { createDirectiveArg(it, directive) }
        directive.populate(
            unfilteredDef.isRepeatable,
            unfilteredDef.allowedLocations,
            unfilteredDef.sourceLocation,
            args
        )
    }

    // ========== Helper: Field and Args ==========

    private fun createField(
        unfilteredField: ViaductSchema.Field,
        containingExtension: ViaductSchema.Extension<SchemaWithData.Record, SchemaWithData.Field>
    ): SchemaWithData.Field {
        val typeExpr = createTypeExprFromDefs(unfilteredField.type)
        return SchemaWithData.Field(
            containingExtension,
            unfilteredField.name,
            typeExpr,
            remapAppliedDirectives(unfilteredField.appliedDirectives),
            unfilteredField.hasDefault,
            if (unfilteredField.hasDefault) unfilteredField.defaultValue else null,
            unfilteredField,
            argsFactory = { field -> createFieldArgs(field, unfilteredField) }
        )
    }

    private fun createFieldArgs(
        field: SchemaWithData.Field,
        unfilteredField: ViaductSchema.Field
    ): List<SchemaWithData.FieldArg> =
        unfilteredField.args.map { arg ->
            val typeExpr = createTypeExprFromDefs(arg.type)
            SchemaWithData.FieldArg(
                field,
                arg.name,
                typeExpr,
                remapAppliedDirectives(arg.appliedDirectives),
                arg.hasDefault,
                if (arg.hasDefault) arg.defaultValue else null,
                arg
            )
        }

    private fun createDirectiveArg(
        unfilteredArg: ViaductSchema.DirectiveArg,
        directive: SchemaWithData.Directive
    ): SchemaWithData.DirectiveArg {
        val typeExpr = createTypeExprFromDefs(unfilteredArg.type)
        return SchemaWithData.DirectiveArg(
            directive,
            unfilteredArg.name,
            typeExpr,
            remapAppliedDirectives(unfilteredArg.appliedDirectives),
            unfilteredArg.hasDefault,
            if (unfilteredArg.hasDefault) unfilteredArg.defaultValue else null,
            unfilteredArg
        )
    }

    private fun createTypeExprFromDefs(unfilteredTypeExpr: ViaductSchema.TypeExpr<*>): ViaductSchema.TypeExpr<SchemaWithData.TypeDef> {
        val baseTypeDef = filteredTypes[unfilteredTypeExpr.baseTypeDef.name]
            ?: error("${unfilteredTypeExpr.baseTypeDef.name} not found in filtered types")
        return ViaductSchema.TypeExpr(baseTypeDef, unfilteredTypeExpr.baseTypeNullable, unfilteredTypeExpr.listNullable)
    }
}

/**
 * Encapsulates logic for projecting a schema. Allows one to
 * remove type-defs, fields, and enumeration values from
 * schemas. You can also remove the supertypes of object
 * and interface types. You cannot remove directive
 * definitions nor can you remove applied directives from
 * any schema element.
 */
interface SchemaFilter {
    fun includeTypeDef(typeDef: ViaductSchema.TypeDef): Boolean

    fun includeField(field: ViaductSchema.Field): Boolean

    fun includeEnumValue(enumValue: ViaductSchema.EnumValue): Boolean

    fun includeSuper(
        record: ViaductSchema.OutputRecord,
        superInterface: ViaductSchema.Interface
    ): Boolean
}
