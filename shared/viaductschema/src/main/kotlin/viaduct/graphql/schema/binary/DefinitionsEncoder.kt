package viaduct.graphql.schema.binary

import graphql.language.NullValue
import graphql.language.Value
import viaduct.graphql.schema.ViaductSchema

internal fun SchemaEncoder.encodeDefinitions() {
    out.writeInt(MAGIC_DEFINITIONS)

    // Write directives first, in alphabetical order by name
    val sortedDirectives = schemaInfo.inputSchema.directives.values.sortedBy { it.name }
    for (directive in sortedDirectives) {
        encodeDirective(directive)
    }

    // Write type definitions in alphabetical order
    for (name in schemaInfo.identifiers) {
        schemaInfo.inputSchema.types[name]?.let { encodeTypeDef(it) }
    }
}

private fun SchemaEncoder.encodeDirective(directive: ViaductSchema.Directive) {
    // Write name reference (identifier index with high bits unused)
    out.writeInt(schemaInfo.identifierIndex(directive.name))

    // Write RefPlus (source location index, no hasImplementedTypes for directives)
    val sourceName = directive.sourceLocation?.sourceName
    val refPlus = DefinitionRefPlus(schemaInfo.sourceNameIndex(sourceName))
    out.writeInt(refPlus.word)

    // Build and write directive info word
    val directiveInfo = DirectiveInfo(directive)
    out.writeInt(directiveInfo.word)

    // Encode arguments if present.
    // For directive definition arguments, we must encode ALL applied directive arguments
    // explicitly (no omission optimization) because circular directive dependencies are
    // allowed and the decoder may not have access to the directive definition yet.
    if (directiveInfo.hasArgs()) {
        encodeArgs(directive.args, allowOmitAppliedDirectiveArgs = false)
    }
}

private fun SchemaEncoder.encodeTypeDef(td: ViaductSchema.TypeDef) {
    // Write name reference (identifier index with high bits unused)
    out.writeInt(schemaInfo.identifierIndex(td.name))

    when (td) {
        is ViaductSchema.Enum -> {
            encodeExtensionList(td.extensions) { ext ->
                encodeEnumValuesOrMarker(ext.members)
            }
        }

        is ViaductSchema.Input -> {
            encodeExtensionList(td.extensions) { ext ->
                encodeInputFieldsOrMarker(ext.members)
            }
        }

        is ViaductSchema.Record -> {
            // Since it's not an Input, must be either Interface or Object
            encodeExtensionList(
                td.extensions,
                hasImplementedTypes = { (it as ViaductSchema.ExtensionWithSupers).supers.isNotEmpty }
            ) { ext ->
                val extWithSupers = ext as ViaductSchema.ExtensionWithSupers
                if (extWithSupers.supers.isNotEmpty) {
                    encodeTypeDefs(extWithSupers.supers)
                }
                encodeFieldsOrMarker(ext.members)
            }
            if (td is ViaductSchema.Interface) {
                encodeTypeDefsOrMarker(td.possibleObjectTypes)
            } else {
                encodeTypeDefsOrMarker(td.unions)
            }
        }

        is ViaductSchema.Union -> {
            encodeExtensionList(td.extensions) { ext ->
                encodeTypeDefsOrMarker(ext.members)
            }
        }

        is ViaductSchema.Scalar -> {
            val hasAppliedDirectives = td.appliedDirectives.isNotEmpty()
            val refPlus = DefinitionRefPlus(
                schemaInfo.sourceNameIndex(td.sourceLocation?.sourceName),
                hasImplementedTypes = false,
                hasAppliedDirectives = hasAppliedDirectives,
                hasNext = false // Only one extension
            )
            out.writeInt(refPlus.word)
            if (hasAppliedDirectives) {
                encodeAppliedDirectives(td.appliedDirectives)
            }
        }

        else -> throw IllegalArgumentException("Unknown GraphQL kind ($td).")
    }
}

//
// Helper functions

/**
 * Generic function to encode extension lists for any type kind.
 * Handles the common pattern of writing DefinitionRefPlus, applied directives,
 * and delegates member encoding to the provided lambda.
 */
private inline fun <E : ViaductSchema.Extension<*, *>> SchemaEncoder.encodeExtensionList(
    extensions: Iterable<E>,
    crossinline hasImplementedTypes: (E) -> Boolean = { false },
    crossinline encodeMembers: (E) -> Unit
) {
    extensions.encode { ext, hasNext ->
        val hasAppliedDirectives = ext.appliedDirectives.isNotEmpty()
        val refPlus = DefinitionRefPlus(
            schemaInfo.sourceNameIndex(ext.sourceLocation?.sourceName),
            hasImplementedTypes = hasImplementedTypes(ext),
            hasAppliedDirectives = hasAppliedDirectives,
            hasNext = hasNext
        )
        out.writeInt(refPlus.word)
        if (hasAppliedDirectives) {
            encodeAppliedDirectives(ext.appliedDirectives)
        }
        encodeMembers(ext)
    }
}

/**
 * Generic function to encode a list or write EMPTY_LIST_MARKER if empty.
 * Replaces the "*OrMarker" pattern used throughout the encoder.
 */
private inline fun <T> SchemaEncoder.encodeListOrMarker(
    items: Iterable<T>,
    encode: (Iterable<T>) -> Unit
) {
    if (items.isNotEmpty) {
        encode(items)
    } else {
        out.writeInt(EMPTY_LIST_MARKER)
    }
}

/**
 * Encode applied directives.
 * @param allowOmitArgs If true, arguments matching defaults may be omitted (optimization).
 *                      If false, ALL arguments are encoded explicitly.
 *                      This should be false when encoding applied directives on directive
 *                      definition arguments, since circular directive dependencies are allowed
 *                      and the decoder may not have access to the directive definition.
 */
private fun SchemaEncoder.encodeAppliedDirectives(
    appliedDirectives: Iterable<ViaductSchema.AppliedDirective>,
    allowOmitArgs: Boolean = true
) {
    appliedDirectives.encode { ad, hasNext ->
        // Filter arguments to only include those that need to be explicitly encoded
        // (unless allowOmitArgs is false, in which case all args are encoded)
        val argsToEncode = if (allowOmitArgs) {
            filterAppliedDirectiveArguments(ad)
        } else {
            ad.arguments.entries.map { it.key to it.value }.sortedBy { it.first }
        }
        val hasArguments = argsToEncode.isNotEmpty()
        val refPlus = AppliedDirectiveRefPlus(
            schemaInfo.identifierIndex(ad.name),
            hasArguments,
            hasNext
        )
        out.writeInt(refPlus.word)
        if (hasArguments) {
            encodeAppliedDirectiveArguments(ad, argsToEncode)
        }
    }
}

/**
 * Filter applied directive arguments to exclude those that can be reconstructed from defaults.
 * An argument is omitted when:
 * - Its value matches the default value in the directive definition, OR
 * - Its value is null and the argument type is nullable (and has no default)
 */
private fun SchemaEncoder.filterAppliedDirectiveArguments(appliedDirective: ViaductSchema.AppliedDirective): List<Pair<String, Any?>> {
    val directiveDef = requireNotNull(schemaInfo.inputSchema.directives[appliedDirective.name]) {
        "Unknown directive: ${appliedDirective.name}"
    }

    return appliedDirective.arguments.entries
        .filter { (argName, argValue) ->
            val argDef = requireNotNull(directiveDef.args.find { it.name == argName }) {
                "Unknown directive argument: ${appliedDirective.name}.$argName"
            }
            !canOmitArgument(argDef, argValue)
        }
        .map { it.key to it.value }
        .sortedBy { it.first } // Sort by name for deterministic encoding
}

/**
 * Check if an argument can be omitted because the decoder will reconstruct the same value.
 */
private fun canOmitArgument(
    argDef: ViaductSchema.Arg,
    argValue: Any?
): Boolean {
    return when {
        // If argument has a default and value matches it, omit
        argDef.hasDefault && valuesEqual(argDef.defaultValue, argValue) -> true
        // If argument is nullable (no default) and value is null, omit
        !argDef.hasDefault && argDef.type.isNullable && argValue == null -> true
        // Otherwise, must encode explicitly
        else -> false
    }
}

/**
 * Compare two argument values for equality.
 */
private fun valuesEqual(
    a: Any?,
    b: Any?
): Boolean {
    // Handle null cases
    if (a == null && b == null) return true
    if (a == null || b == null) return false
    // Use equals for everything else
    return a == b
}

private fun SchemaEncoder.encodeAppliedDirectiveArguments(
    appliedDirective: ViaductSchema.AppliedDirective,
    argsToEncode: List<Pair<String, Any?>>
) {
    val directiveDef = schemaInfo.inputSchema.directives[appliedDirective.name]
        ?: throw IllegalArgumentException("Unknown directive: ${appliedDirective.name}")
    argsToEncode.encode { (argName, argValue), hasNext ->
        val refPlus = AppliedDirectiveArgRefPlus(schemaInfo.identifierIndex(argName), hasNext)
        out.writeInt(refPlus.word)
        // Convert value to string representation
        val argDef = directiveDef.args.find { it.name == argName }
            ?: throw IllegalArgumentException("Unknown argument $argName for directive ${appliedDirective.name}")
        val value = argValue as? Value<*>
            ?: NullValue.newNullValue().build()
        val constantRepr = ValueStringConverter.valueToString(value)
        out.writeInt(constantsEncoder.findRef(constantRepr))
    }
}

private fun SchemaEncoder.encodeTypeDefs(typeDefs: Iterable<ViaductSchema.TypeDef>) {
    typeDefs.encode { v, hasNext ->
        out.writeInt(schemaInfo.identifierIndex(v.name).tagIf(!hasNext))
    }
}

private fun SchemaEncoder.encodeTypeDefsOrMarker(typeDefs: Iterable<ViaductSchema.TypeDef>) {
    encodeListOrMarker(typeDefs) { encodeTypeDefs(it) }
}

/**
 * Encode a single input-like field (name + type + optional constant reference).
 * Used for directive args, field args, and input object fields.
 *
 * @param allowOmitAppliedDirectiveArgs If true, applied directive arguments matching defaults
 *                                       may be omitted. Should be false for directive definition args.
 */
private fun SchemaEncoder.encodeInputLikeField(
    name: String,
    type: ViaductSchema.TypeExpr,
    hasDefault: Boolean,
    defaultValue: Any?,
    hasNext: Boolean,
    appliedDirectives: Collection<ViaductSchema.AppliedDirective> = emptyList(),
    allowOmitAppliedDirectiveArgs: Boolean = true
) {
    val hasAppliedDirs = appliedDirectives.isNotEmpty()
    val refPlus = InputLikeFieldRefPlus(
        nameIndex = schemaInfo.identifierIndex(name),
        hasDefaultValue = hasDefault,
        hasAppliedDirectives = hasAppliedDirs,
        hasNext = hasNext
    )
    out.writeInt(refPlus.word)
    if (hasAppliedDirs) {
        encodeAppliedDirectives(appliedDirectives, allowOmitAppliedDirectiveArgs)
    }
    out.writeInt(schemaInfo.typeExprs[type]!!)
    if (hasDefault) {
        out.writeInt(constantsEncoder.findRef(defaultValue))
    }
}

/**
 * Encode a list of arguments.
 *
 * @param allowOmitAppliedDirectiveArgs If true, applied directive arguments matching defaults
 *                                       may be omitted. Should be false for directive definition args.
 */
private fun SchemaEncoder.encodeArgs(
    args: Iterable<ViaductSchema.Arg>,
    allowOmitAppliedDirectiveArgs: Boolean = true
) {
    args.encode { arg, hasNext ->
        val defaultValue = if (arg.hasDefault) {
            // Handle explicit null default values - ViaductSchema returns Java null instead of NullValue
            val value = arg.defaultValue as? Value<*>
                ?: NullValue.newNullValue().build()
            ValueStringConverter.valueToString(value)
        } else {
            null
        }
        encodeInputLikeField(
            arg.name,
            arg.type,
            arg.hasDefault,
            defaultValue,
            hasNext,
            arg.appliedDirectives,
            allowOmitAppliedDirectiveArgs
        )
    }
}

/**
 * Encode fields for Input types using input-like field encoding.
 * Input fields cannot have arguments, so they use the simpler encoding.
 */
private fun SchemaEncoder.encodeInputFields(fields: Iterable<ViaductSchema.Field>) {
    fields.encode { field, hasNext ->
        val defaultValue = if (field.hasDefault) {
            // Handle explicit null default values - ViaductSchema returns Java null instead of NullValue
            val value = field.defaultValue as? Value<*>
                ?: NullValue.newNullValue().build()
            ValueStringConverter.valueToString(value)
        } else {
            null
        }
        encodeInputLikeField(field.name, field.type, field.hasDefault, defaultValue, hasNext, field.appliedDirectives)
    }
}

private fun SchemaEncoder.encodeInputFieldsOrMarker(fields: Iterable<ViaductSchema.Field>) {
    encodeListOrMarker(fields) { encodeInputFields(it) }
}

private fun SchemaEncoder.encodeEnumValuesOrMarker(values: Iterable<ViaductSchema.EnumValue>) {
    encodeListOrMarker(values) { vals ->
        vals.encode { v, hasNext ->
            val hasAppliedDirs = v.appliedDirectives.isNotEmpty()
            val refPlus = EnumValueRefPlus(
                schemaInfo.identifierIndex(v.name),
                hasAppliedDirs,
                hasNext
            )
            out.writeInt(refPlus.word)
            if (hasAppliedDirs) {
                encodeAppliedDirectives(v.appliedDirectives)
            }
        }
    }
}

/**
 * Encode fields for Object and Interface types.
 * These fields may have arguments, so they use a more complex encoding.
 */
private fun SchemaEncoder.encodeFields(fields: Iterable<ViaductSchema.Field>) {
    fields.encode { field, hasNext ->
        val hasAppliedDirs = field.appliedDirectives.isNotEmpty()
        val hasArgs = field.args.isNotEmpty
        val refPlus = OutputFieldRefPlus(
            nameIndex = schemaInfo.identifierIndex(field.name),
            hasArguments = hasArgs,
            hasAppliedDirectives = hasAppliedDirs,
            hasNext = hasNext
        )
        out.writeInt(refPlus.word)
        if (hasAppliedDirs) {
            encodeAppliedDirectives(field.appliedDirectives)
        }
        out.writeInt(schemaInfo.typeExprs[field.type]!!)
        if (hasArgs) {
            encodeArgs(field.args)
        }
    }
}

private fun SchemaEncoder.encodeFieldsOrMarker(fields: Iterable<ViaductSchema.Field>) {
    encodeListOrMarker(fields) { encodeFields(it) }
}

private inline fun Int.tagIf(needsTag: Boolean) = this or (if (needsTag) (1 shl 31) else 0)

private inline val Iterable<*>.isNotEmpty get() = this.iterator().hasNext()

private fun <T> Iterable<T>.encode(encoder: (T, Boolean) -> Unit) {
    val i = this.iterator()
    var hasNext = i.hasNext()
    while (hasNext) {
        val v = i.next()
        hasNext = i.hasNext()
        encoder(v, hasNext)
    }
}
