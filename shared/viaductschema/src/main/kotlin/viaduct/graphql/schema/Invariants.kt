@file:Suppress("MatchingDeclarationName")

package viaduct.graphql.schema

import viaduct.invariants.InvariantChecker

data class SchemaInvariantOptions(
    val allowEmptyTypes: Boolean
) {
    companion object {
        val DEFAULT = SchemaInvariantOptions(allowEmptyTypes = false)
        val ALLOW_EMPTY_TYPES = SchemaInvariantOptions(allowEmptyTypes = true)
    }
}

fun checkBridgeSchemaInvariants(
    schema: ViaductSchema,
    check: InvariantChecker,
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
            directive.args.forEach {
                check.withContext(it.name) {
                    checkTypeExprReferentialIntegrity(schema, it.type, check)
                }
            }
            check.isNotEmpty(directive.allowedLocations, "DIRECTIVE_LOCATIONS_EMPTY")
        }
    }

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

private fun checkBackPointerInvariants(
    def: ViaductSchema.TypeDef,
    check: InvariantChecker
) {
    when (def) {
        is ViaductSchema.Directive ->
            def.args.forEach {
                check.withContext(it.name) {
                    check.isSameInstanceAs(def, it.containingDef, "BACKPOINTER")
                }
            }

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
    type: ViaductSchema.TypeExpr,
    check: InvariantChecker
) {
    val n = type.baseTypeDef.name
    check.isSameInstanceAs(schema.types[n]!!, type.baseTypeDef, "TYPE_EXPR_BASE_INTEGRITY")
    check.isSameInstanceAs(schema.types[n]!!, type.unwrapLists().baseTypeDef, "TYPE_EXPR_UNWRAP_INTEGRITY")
}

fun checkExtensionReferentialIntegrity(
    schema: ViaductSchema,
    containingDef: ViaductSchema.HasExtensions<*, *>,
    allExpectedMembers: Iterable<*>,
    allExpectedSupers: Iterable<ViaductSchema.Interface>?,
    check: InvariantChecker
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
        containingDef as ViaductSchema.HasExtensionsWithSupers<*, *>
        val allActualSupers = containingDef.extensions.flatMap { it.supers }
        check.containsNoDuplicates(allActualSupers.map { it.name }, "EXTENSION_SUPERS_NO_DUPLICATES")
        check.containsExactlyElementsIn(allExpectedSupers, allActualSupers, "EXTENSION_SUPERS_EXHAUSTIVE")
    }
}

private fun checkReferentialIntegrity(
    schema: ViaductSchema,
    def: ViaductSchema.TypeDef,
    check: InvariantChecker
) {
    check.isSameInstanceAs(schema.types[def.name]!!, def, "DEF_INTEGRITY")
    checkTypeExprReferentialIntegrity(schema, def.asTypeExpr(), check)
    def.possibleObjectTypes.forEach {
        check.isSameInstanceAs(schema.types[it.name]!!, it, "POSSIBLE_OBJECT_TYPE_INTEGRITY ${it.name}")
    }

    val allExpectedSupers =
        when (def) {
            is ViaductSchema.HasExtensionsWithSupers<*, *> -> def.supers
            else -> null
        }
    if (def is ViaductSchema.Enum) {
        checkExtensionReferentialIntegrity(schema, def, def.values, allExpectedSupers, check)
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
            def as ViaductSchema.HasExtensions<*, *>,
            def.fields,
            allExpectedSupers,
            check
        )
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
        def.supers.forEach { check.isSameInstanceAs(schema.types[it.name]!!, it, "SUP_INTEGRITY ${it.name}") }
        def.unions.forEach { check.isSameInstanceAs(schema.types[it.name]!!, it, "UNION_INTEGRITY ${it.name}") }
    }

    if (def is ViaductSchema.Union) {
        checkExtensionReferentialIntegrity(schema, def, def.possibleObjectTypes, allExpectedSupers, check)
    }
}

private fun checkEmptyListInvariants(
    def: ViaductSchema.TypeDef,
    check: InvariantChecker
) {
    when (def) {
        is ViaductSchema.Enum -> { }

        is ViaductSchema.Input -> {
            check.isEmpty(def.supers, "SUPERS_EMPTY")
            check.isEmpty(def.unions, "UNIONS_EMPTY")
        }

        is ViaductSchema.Interface -> {
            check.isEmpty(def.unions, "UNIONS_EMPTY")
        }

        is ViaductSchema.Object -> { }
        is ViaductSchema.Scalar -> { }
        is ViaductSchema.Union -> { }
        else -> throw IllegalArgumentException("Unknown type ($def).")
    }
}

private fun checkExtensionsInvariants(
    def: ViaductSchema.TypeDef,
    check: InvariantChecker
) {
    if (def is ViaductSchema.HasExtensions<*, *>) {
        check.isNotEmpty(def.extensions, "EXTENSIONS_NOT_EMPTY")
        val exts = def.extensions.iterator()
        check.isTrue(exts.next().isBase, "FIRST_EXTENSION_IS_BASE")
        var i = 1
        while (exts.hasNext()) {
            check.isFalse(exts.next().isBase, "OTHER_EXTENSIONS_ARE_NOT_BASE(${i++})")
        }
    }
}

private fun checkToTypeExprInvariants(
    def: ViaductSchema.TypeDef,
    check: InvariantChecker
) {
    check.isEqualTo("?", def.asTypeExpr().unparseWrappers(), "TO_TYPE_EXPR_NOT_NULLABLE")
    check.isSameInstanceAs(def, def.asTypeExpr().baseTypeDef, "TO_TYPE_EXPR_BASETYPE")
}

private fun checkValidSchemaInvariants(
    def: ViaductSchema.TypeDef,
    check: InvariantChecker,
    options: SchemaInvariantOptions
) {
    when (def) {
        is ViaductSchema.Enum -> {
            check.isNotEmpty(def.values, "ENUM_VALUES_NOT_EMPTY")
        }
        is ViaductSchema.Input -> {
            check.isNotEmpty(def.fields, "INPUT_FIELDS_NOT_EMPTY")
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
    check: InvariantChecker
) {
    if (def is ViaductSchema.TypeDef) {
        val isSimple = def is ViaductSchema.Scalar || def is ViaductSchema.Enum
        check.isEqualTo(isSimple, def.isSimple, "CORRECT_IS_SIMPLE")
        val isComposite = def is ViaductSchema.Object || def is ViaductSchema.Interface || def is ViaductSchema.Union
        check.isEqualTo(isComposite, def.isComposite, "CORRECT_IS_COMPOUND")
        val isInput = def is ViaductSchema.Input
        check.isEqualTo(isInput, def.isInput, "CORRECT_IS_INPUT")
        val isOutput = def !is ViaductSchema.Input
        check.isEqualTo(isOutput, def.isOutput, "CORRECT_IS_OUTPUT")
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
