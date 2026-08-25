@file:Suppress("MatchingDeclarationName")

package viaduct.graphql.schema

import viaduct.invariants.FailureCollector

/**
 * Flags to turn on and off invariance checks.  Right
 * now the only flag is [allowEmptyTypes], which controls
 * whether or not a type with no fields is considered
 * an error.
 */
data class SchemaInvariantOptions(
    val allowEmptyTypes: Boolean
) {
    companion object {
        val DEFAULT = SchemaInvariantOptions(allowEmptyTypes = false)
        val ALLOW_EMPTY_TYPES = SchemaInvariantOptions(allowEmptyTypes = true)
    }
}

/**
 * Checks invariants expected of a [ViaductSchema] instance, e.g.,
 * referential integrity (e.g., [Field.containingDef] of a member
 * of [Record.fields] points back to the record) and structural
 * integrity (e.g., `asTypeExpr` is not a list).  While some of these
 * checks correspond to GraphQL validation rules, this function does
 * _not_ fully validate GraphQL schemas.
 */
fun checkViaductSchemaInvariants(
    schema: ViaductSchema,
    check: FailureCollector,
    options: SchemaInvariantOptions = SchemaInvariantOptions.DEFAULT
) {
    check.isEqualTo(schema.types.values.size, schema.types.size, "TYPE_DEFS_SIZE")
    check.isEqualTo(schema.types.entries.size, schema.types.size, "ENTRIES_SIZE")
    check.isEqualTo(schema.types.keys.size, schema.types.size, "NAMES_SIZE")
    check.isEqualTo(
        schema.types.values
            .map { it.name }
            .toSet(),
        schema.types.keys,
        "NAMES_SET"
    )
    check.isEqualTo(
        schema.types.entries
            .map { it.key }
            .toSet(),
        schema.types.keys,
        "ENTRIES_KEYS"
    )
    for (entry in schema.types.entries) {
        check.withContext(entry.key) {
            check.isSameInstanceAs(entry.value, schema.types[entry.key]!!, "ENTRIES_VALUES")
        }
    }

    for ((directiveName, directive) in schema.directives) {
        check.withContext(directiveName) {
            check.isEqualTo(directiveName, directive.name, "DIRECTIVE_NAME")
            check.isSameInstanceAs(directive, schema.directives[directiveName]!!, "DIRECTIVE_INTEGRITY")
            check.isSameInstanceAs(schema, directive.containingSchema, "DIRECTIVE_SCHEMA_INTEGRITY")
            directive.args.forEach {
                check.withContext(it.name) {
                    check.isSameInstanceAs(directive, it.containingDef, "DIRECTIVE_ARG_BACKPOINTER")
                    checkTypeExprReferentialIntegrity(schema, it.type, check)
                }
            }
            check.isNotEmpty(directive.allowedLocations, "DIRECTIVE_LOCATIONS_EMPTY")
        }
    }

    checkRootReferentialIntegrity(schema, schema.queryTypeDef, "QUERY_ROOT_INTEGRITY", check)
    checkRootReferentialIntegrity(schema, schema.mutationTypeDef, "MUTATION_ROOT_INTEGRITY", check)
    checkRootReferentialIntegrity(schema, schema.subscriptionTypeDef, "SUBSCRIPTION_ROOT_INTEGRITY", check)

    for (def in schema.types.values) {
        check.withContext(def.name) {
            checkBackPointerInvariants(def, check)
            checkReferentialIntegrity(schema, def, check)
            checkEmptyListInvariants(def, check)
            checkExtensionsInvariants(def, check)
            checkToTypeExprInvariants(def, check)
            checkValidSchemaInvariants(def, check, options)
            checkMiscInvariants(def, check)
        }
    }
}

private fun checkRootReferentialIntegrity(
    schema: ViaductSchema,
    root: ViaductSchema.Object?,
    message: String,
    check: FailureCollector
) {
    if (root != null) {
        val canonicalRoot = schema.types[root.name]
        check.isNotNull(canonicalRoot, message)
        if (canonicalRoot != null) {
            check.isSameInstanceAs(canonicalRoot, root, message)
        }
    }
}

private fun checkBackPointerInvariants(
    def: ViaductSchema.TypeDef,
    check: FailureCollector
) {
    when (def) {
        is ViaductSchema.Enum ->
            def.values.forEach {
                check.withContext(it.name) {
                    check.isSameInstanceAs(def, it.containingDef, "BACKPOINTER")
                }
            }

        is ViaductSchema.Record ->
            def.fields.forEach { field ->
                check.withContext(field.name) {
                    check.isSameInstanceAs(def, field.containingDef, "BACKPOINTER")
                    field.args.forEach { arg ->
                        check.withContext(arg.name) {
                            check.isSameInstanceAs(field, arg.containingDef, "BACKPOINTER")
                        }
                    }
                }
            }

        is ViaductSchema.Scalar -> { }
        is ViaductSchema.Union -> { }
        else -> throw IllegalArgumentException("Unknown type ($def).")
    }
}

fun checkTypeExprReferentialIntegrity(
    schema: ViaductSchema,
    type: ViaductSchema.TypeExpr<*>,
    check: FailureCollector
) {
    val n = type.baseTypeDef.name
    check.isSameInstanceAs(schema.types[n]!!, type.baseTypeDef, "TYPE_EXPR_BASE_INTEGRITY")
    check.isSameInstanceAs(schema.types[n]!!, type.unwrapLists().baseTypeDef, "TYPE_EXPR_UNWRAP_INTEGRITY")
}

fun checkExtensionReferentialIntegrity(
    schema: ViaductSchema,
    containingDef: ViaductSchema.TypeDef,
    allExpectedMembers: Iterable<*>,
    allExpectedSupers: Iterable<ViaductSchema.Interface>?,
    check: FailureCollector
) {
    for (ext in containingDef.extensions) {
        check.withContext(ext.members.joinToString("::") { it.name }) {
            check.isSameInstanceAs(schema.types[ext.def.name]!!, ext.def, "EXTENSION_DEF_INTEGRITY")
            check.containsAtMostElementsIn(allExpectedMembers, ext.members, "EXTENSION_MEMBERS_INTEGRITY")
            check.containsNoDuplicates(ext.members.map { it.name }, "EXTENSION_MEMBERS_NO_DUPLICATES")
            if (allExpectedSupers != null) {
                ext as ViaductSchema.ExtensionWithSupers<*, *>
                check.isNotNull(ext.supers, "EXTENSION_SUPERS_NOT_NULL")
                check.containsAtMostElementsIn(allExpectedSupers, ext.supers, "EXTENSION_SUPERS_INTEGRITY")
                check.containsNoDuplicates(ext.supers.map { it.name }, "EXTENSION_SUPERS_NO_DUPLICATES")
            }
        }
    }
    val allActualMembers = containingDef.extensions.flatMap { it.members }
    check.containsNoDuplicates(allActualMembers.map { it.name }, "EXTENSION_MEMBERS_NO_DUPLICATES")
    check.containsExactlyElementsIn(allExpectedMembers, allActualMembers, "EXTENSION_MEMBERS_EXHAUSTIVE")
    if (allExpectedSupers != null) {
        containingDef as ViaductSchema.OutputRecord
        val allActualSupers = containingDef.extensions.flatMap { it.supers }
        check.containsNoDuplicates(allActualSupers.map { it.name }, "EXTENSION_SUPERS_NO_DUPLICATES")
        check.containsExactlyElementsIn(allExpectedSupers, allActualSupers, "EXTENSION_SUPERS_EXHAUSTIVE")
    }
}

private fun checkReferentialIntegrity(
    schema: ViaductSchema,
    def: ViaductSchema.TypeDef,
    check: FailureCollector
) {
    check.isSameInstanceAs(schema.types[def.name]!!, def, "DEF_INTEGRITY")
    check.isSameInstanceAs(schema, def.containingSchema, "DEF_SCHEMA_INTEGRITY")
    checkTypeExprReferentialIntegrity(schema, def.asTypeExpr(), check)
    def.possibleObjectTypes.forEach {
        check.isSameInstanceAs(schema.types[it.name]!!, it, "POSSIBLE_OBJECT_TYPE_INTEGRITY ${it.name}")
    }
    checkPossibleObjectTypesInvariants(schema, def, check)

    val allExpectedSupers =
        when (def) {
            is ViaductSchema.OutputRecord -> def.supers
            else -> null
        }
    if (def is ViaductSchema.Enum) {
        checkExtensionReferentialIntegrity(schema, def, def.values, allExpectedSupers, check)
        check.isNull(def.value(""), "ENUM_UNKNOWN_VALUE")
        def.values.forEach { value ->
            check.withContext(value.name) {
                check.isSameInstanceAs(value, def.value(value.name)!!, "ENUM_VAL_INTEGRITY")
                check.isSameInstanceAs(def, value.containingDef, "ENUM_VAL_DEF_INTEGRITY")
                check.containedBy(def.extensions, value.containingExtension, "ENUM_VAL_EXT_INTEGRITY")
            }
        }
    }

    if (def is ViaductSchema.Record) {
        checkExtensionReferentialIntegrity(
            schema,
            def,
            def.fields,
            allExpectedSupers,
            check
        )
        check.isNull(def.field(""), "RECORD_UNKNOWN_FIELD")
        def.fields.forEach { field ->
            check.withContext(field.name) {
                check.isSameInstanceAs(field, def.field(field.name)!!, "FIELD_INTEGRITY")
                check.isSameInstanceAs(def, field.containingDef, "FIELD_DEF_INTEGRITY")
                check.containedBy(def.extensions, field.containingExtension, "FIELD_EXT_INTEGRITY")
                field.args.forEach { arg ->
                    check.withContext(arg.name) {
                        check.isSameInstanceAs(field, arg.containingDef, "ARG_DEF_INTEGRITY")
                        checkTypeExprReferentialIntegrity(schema, arg.type, check)
                    }
                }
            }
        }
    }

    if (def is ViaductSchema.OutputRecord) {
        def.supers.forEach { check.isSameInstanceAs(schema.types[it.name]!!, it, "SUP_INTEGRITY ${it.name}") }
        if (def is ViaductSchema.Object) {
            def.unions.forEach { check.isSameInstanceAs(schema.types[it.name]!!, it, "UNION_INTEGRITY ${it.name}") }
            val expectedUnions =
                schema.types.values
                    .filterIsInstance<ViaductSchema.Union>()
                    .filter { union -> union.possibleObjectTypes.any { it === def } }
                    .map { it.name }
            check.containsExactlyElementsIn(expectedUnions, def.unions.map { it.name }, "OBJECT_UNIONS")
        }
    }

    if (def is ViaductSchema.Union) {
        checkExtensionReferentialIntegrity(schema, def, def.possibleObjectTypes, allExpectedSupers, check)
    }
}

private fun checkPossibleObjectTypesInvariants(
    schema: ViaductSchema,
    def: ViaductSchema.TypeDef,
    check: FailureCollector
) {
    val expectedNames =
        when (def) {
            is ViaductSchema.Object -> listOf(def.name)
            is ViaductSchema.Interface ->
                schema.types.values
                    .filterIsInstance<ViaductSchema.Object>()
                    .filter { obj -> obj.supers.any { it === def } }
                    .map { it.name }
            is ViaductSchema.Union -> def.extensions.flatMap { it.members }.map { it.name }
            else -> emptyList()
        }

    check.containsNoDuplicates(def.possibleObjectTypes.map { it.name }, "POSSIBLE_OBJECT_TYPES_NO_DUPLICATES")
    check.containsExactlyElementsIn(
        expectedNames,
        def.possibleObjectTypes.map { it.name },
        "POSSIBLE_OBJECT_TYPES_EXHAUSTIVE"
    )
    if (def is ViaductSchema.Object) {
        check.isEqualTo(1, def.possibleObjectTypes.size, "OBJECT_HAS_ONE_POSSIBLE_TYPE")
        def.possibleObjectTypes.firstOrNull()?.let {
            check.isSameInstanceAs(def, it, "OBJECT_IS_OWN_POSSIBLE_TYPE")
        }
    }
}

private fun checkEmptyListInvariants(
    def: ViaductSchema.TypeDef,
    @Suppress("UNUSED_PARAMETER") // Keep for parallism with other checkXyzInvariants functions
    check: FailureCollector
) {
    when (def) {
        is ViaductSchema.Enum -> { }

        is ViaductSchema.Input -> { }

        is ViaductSchema.Interface -> { }

        is ViaductSchema.Object -> { }
        is ViaductSchema.Scalar -> { }
        is ViaductSchema.Union -> { }
        else -> throw IllegalArgumentException("Unknown type ($def).")
    }
}

private fun checkExtensionsInvariants(
    def: ViaductSchema.TypeDef,
    check: FailureCollector
) {
    check.isNotEmpty(def.extensions, "EXTENSIONS_NOT_EMPTY")
    val exts = def.extensions.iterator()
    check.isTrue(exts.next().isBase, "FIRST_EXTENSION_IS_BASE")
    var i = 1
    while (exts.hasNext()) {
        check.isFalse(exts.next().isBase, "OTHER_EXTENSIONS_ARE_NOT_BASE(${i++})")
    }
}

private fun checkToTypeExprInvariants(
    def: ViaductSchema.TypeDef,
    check: FailureCollector
) {
    check.isEqualTo("?", def.asTypeExpr().unparseWrappers(), "TO_TYPE_EXPR_NOT_NULLABLE")
    check.isSameInstanceAs(def, def.asTypeExpr().baseTypeDef, "TO_TYPE_EXPR_BASETYPE")
}

private fun checkValidSchemaInvariants(
    def: ViaductSchema.TypeDef,
    check: FailureCollector,
    options: SchemaInvariantOptions
) {
    when (def) {
        is ViaductSchema.Enum -> {
            check.isNotEmpty(def.values, "ENUM_VALUES_NOT_EMPTY")
        }
        is ViaductSchema.Input -> {
            if (!options.allowEmptyTypes) {
                check.isNotEmpty(def.fields, "INPUT_FIELDS_NOT_EMPTY")
            }
        }
        is ViaductSchema.Interface -> {
            if (!options.allowEmptyTypes) {
                check.isNotEmpty(def.fields, "INTERFACE_FIELDS_NOT_EMPTY")
            }
        }
        is ViaductSchema.Object -> {
            if (!options.allowEmptyTypes) {
                check.isNotEmpty(def.fields, "OBJECT_FIELDS_NOT_EMPTY")
            }
        }
        is ViaductSchema.Union -> {
            if (!options.allowEmptyTypes) {
                check.isNotEmpty(def.possibleObjectTypes, "UNION_MEMBERS_NOT_EMPTY")
            }
        }
    }
}

private fun checkMiscInvariants(
    def: ViaductSchema.Def,
    check: FailureCollector
) {
    if (def is ViaductSchema.TypeDef) {
        val isSimple = def is ViaductSchema.Scalar || def is ViaductSchema.Enum
        check.isEqualTo(isSimple, def.isSimple, "CORRECT_IS_SIMPLE")
        check.isEqualTo(isSimple, def is ViaductSchema.SimpleTypeDef, "CORRECT_SIMPLE_TYPE_ROLE")
        val isComposite = def is ViaductSchema.Object || def is ViaductSchema.Interface || def is ViaductSchema.Union
        check.isEqualTo(isComposite, def.isComposite, "CORRECT_IS_COMPOUND")
        check.isEqualTo(isComposite, def is ViaductSchema.CompositeTypeDef, "CORRECT_COMPOSITE_TYPE_ROLE")
        val isInput = isSimple || def is ViaductSchema.Input
        check.isEqualTo(isInput, def is ViaductSchema.InputTypeDef, "CORRECT_INPUT_TYPE_ROLE")
        val isOutput = def !is ViaductSchema.Input
        check.isEqualTo(isOutput, def.isOutput, "CORRECT_IS_OUTPUT")
        check.isEqualTo(isOutput, def is ViaductSchema.OutputTypeDef, "CORRECT_OUTPUT_TYPE_ROLE")
        when (def) {
            is ViaductSchema.Enum -> check.isEqualTo(ViaductSchema.TypeDefKind.ENUM, def.kind, "CORRECT_ENUM")
            is ViaductSchema.Input -> check.isEqualTo(ViaductSchema.TypeDefKind.INPUT, def.kind, "CORRECT_INPUT")
            is ViaductSchema.Interface ->
                check.isEqualTo(ViaductSchema.TypeDefKind.INTERFACE, def.kind, "CORRECT_INTERFACE")
            is ViaductSchema.Object -> check.isEqualTo(ViaductSchema.TypeDefKind.OBJECT, def.kind, "CORRECT_OBJECT")
            is ViaductSchema.Scalar -> check.isEqualTo(ViaductSchema.TypeDefKind.SCALAR, def.kind, "CORRECT_SCALAR")
            is ViaductSchema.Union -> check.isEqualTo(ViaductSchema.TypeDefKind.UNION, def.kind, "CORRECT_UNION")
            else -> throw IllegalArgumentException("Unknown type ($def).")
        }
    } else {
        check.pushContext(def.name)
    }

    for (ad in def.appliedDirectives) {
        check.withContext("@${ad.name}") {
            check.isTrue(def.hasAppliedDirective(ad.name), "CORRECT_PRESENT_DIRECTIVE")
        }
    }
    for (adn in listOf("thisWillNeverBeTheNameOfADirective", "", "__directive")) {
        check.withContext("@$adn") {
            check.isFalse(def.hasAppliedDirective(adn), "CORRECT_ABSENT_DIRECTIVE")
        }
    }

    if (def is ViaductSchema.HasDefaultValue) {
        if (def.hasDefault) {
            check.doesNotThrow("HAS_DEFAULTS_NO_THROW") { def.defaultValue }
        } else {
            check.doesThrow<NoSuchElementException>("HAS_DEFAULTS_THROWS") { def.defaultValue }
        }
        if (def.hasEffectiveDefault) {
            check.doesNotThrow("HAS_EDEFAULTS_NO_THROW") { def.effectiveDefaultValue }
        } else {
            check.doesThrow<NoSuchElementException>("HAS_EDEFAULTS_THROWS") { def.effectiveDefaultValue }
        }
    }

    when (def) {
        is ViaductSchema.Record -> def.fields.forEach { checkMiscInvariants(it, check) }
        is ViaductSchema.Enum -> def.values.forEach { checkMiscInvariants(it, check) }
        is ViaductSchema.Field ->
            check.withContext(def.name) {
                check.isEqualTo(def.args.isNotEmpty(), def.hasArgs, "CORRECT_HAS_ARGS")
                def.args.forEach { checkMiscInvariants(it, check) }
            }
    }
    if (def !is ViaductSchema.TypeDef) check.popContext()
}
