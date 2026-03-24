package viaduct.graphql.schema.binary

import viaduct.graphql.schema.ViaductSchema

internal fun SchemaEncoder.encodeDefinitions() {
    out.writeInt(MAGIC_DEFINITIONS)

    // Write directives first, in topological order (dependencies before dependents).
    // This ensures that when decoding, the directive definition is always available
    // when decoding applied directives that reference it.
    val sortedDirectives = topologicalSortDirectives(schemaInfo.inputSchema.directives.values)
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
    // Since directives are written in topological order, the decoder will always have
    // access to referenced directive definitions when reconstructing omitted arguments.
    if (directiveInfo.hasArgs()) {
        encodeArgs(directive.args)
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
                encodeTypeDefsOrMarker((td as ViaductSchema.Object).unions)
            }
        }

        is ViaductSchema.Union -> {
            encodeExtensionList(td.extensions) { ext ->
                encodeTypeDefsOrMarker(ext.members)
            }
        }

        is ViaductSchema.Scalar -> {
            td.extensions.encode { ext, hasNext ->
                val hasAppliedDirectives = ext.appliedDirectives.isNotEmpty()
                val refPlus = DefinitionRefPlus(
                    schemaInfo.sourceNameIndex(ext.sourceLocation?.sourceName),
                    hasImplementedTypes = false,
                    hasAppliedDirectives = hasAppliedDirectives,
                    hasNext = hasNext
                )
                out.writeInt(refPlus.word)
                if (hasAppliedDirectives) {
                    encodeAppliedDirectives(ext.appliedDirectives)
                }
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
 * Encode applied directives. Arguments matching defaults are omitted as an optimization;
 * the decoder reconstructs them using the directive definition. This works because directives
 * are encoded in topological order, ensuring definitions are always available when decoding.
 */
private fun SchemaEncoder.encodeAppliedDirectives(appliedDirectives: Iterable<ViaductSchema.AppliedDirective<*>>) {
    appliedDirectives.encode { ad, hasNext ->
        // Filter arguments to only include those that need to be explicitly encoded.
        // Arguments matching defaults can be omitted since the decoder can reconstruct them.
        // This works for all applied directives because directives are encoded in topological
        // order, ensuring the directive definition is always available when decoding.
        val argsToEncode = filterAppliedDirectiveArguments(ad)
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
private fun SchemaEncoder.filterAppliedDirectiveArguments(appliedDirective: ViaductSchema.AppliedDirective<*>): List<Pair<String, ViaductSchema.Literal>> {
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
    argDef: ViaductSchema.HasDefaultValue,
    argValue: ViaductSchema.Literal
): Boolean {
    return when {
        // If argument has a default and value matches it, omit
        argDef.hasDefault && valuesEqual(argDef.defaultValue, argValue) -> true
        // If argument is nullable (no default) and value is NullValue, omit
        // (the decoder reconstructs NullValue for nullable args without defaults)
        !argDef.hasDefault && argDef.type.isNullable && argValue is ViaductSchema.NullLiteral -> true
        // Otherwise, must encode explicitly
        else -> false
    }
}

/**
 * Get the default value as a representation suitable for encoding, or null if no default.
 */
private fun ViaductSchema.HasDefaultValue.defaultValueRepr(): Any? {
    if (!hasDefault) return null
    // defaultValue is ViaductSchema.Literal (non-nullable) when hasDefault is true;
    // GraphQL null values are represented as NullValue, not Kotlin null
    return ValueStringConverter.valueToString(defaultValue)
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
    appliedDirective: ViaductSchema.AppliedDirective<*>,
    argsToEncode: List<Pair<String, ViaductSchema.Literal>>
) {
    val directiveDef = schemaInfo.inputSchema.directives[appliedDirective.name]
        ?: throw IllegalArgumentException("Unknown directive: ${appliedDirective.name}")
    argsToEncode.encode { (argName, argValue), hasNext ->
        val refPlus = AppliedDirectiveArgRefPlus(schemaInfo.identifierIndex(argName), hasNext)
        out.writeInt(refPlus.word)
        // Validate argument exists in directive definition
        require(directiveDef.args.any { it.name == argName }) {
            "Unknown argument $argName for directive ${appliedDirective.name}"
        }
        // argValue is ViaductSchema.Literal from appliedDirective.arguments;
        // GraphQL null values are represented as NullValue, not Kotlin null
        val constantRepr = ValueStringConverter.valueToString(argValue)
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
 */
private fun SchemaEncoder.encodeInputLikeField(
    name: String,
    type: ViaductSchema.TypeExpr<*>,
    hasDefault: Boolean,
    defaultValue: Any?,
    hasNext: Boolean,
    appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>> = emptyList()
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
        encodeAppliedDirectives(appliedDirectives)
    }
    out.writeInt(schemaInfo.typeExprs[type]!!)
    if (hasDefault) {
        out.writeInt(constantsEncoder.findRef(defaultValue))
    }
}

/**
 * Encode a list of arguments.
 */
private fun SchemaEncoder.encodeArgs(args: Iterable<ViaductSchema.Arg>) {
    args.encode { arg, hasNext ->
        encodeInputLikeField(
            arg.name,
            arg.type,
            arg.hasDefault,
            arg.defaultValueRepr(),
            hasNext,
            arg.appliedDirectives
        )
    }
}

/**
 * Encode fields for Input types using input-like field encoding.
 * Input fields cannot have arguments, so they use the simpler encoding.
 */
private fun SchemaEncoder.encodeInputFields(fields: Iterable<ViaductSchema.Field>) {
    fields.encode { field, hasNext ->
        encodeInputLikeField(field.name, field.type, field.hasDefault, field.defaultValueRepr(), hasNext, field.appliedDirectives)
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

@Suppress("NOTHING_TO_INLINE")
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

//
// Topological sort for directives
//

/**
 * Topological sort of directives using DFS with gray coloring.
 *
 * A directive A depends on directive B if A has @B applied to any of its arguments.
 * The GraphQL spec prohibits circular directive references, so this sort should
 * always succeed for valid schemas. If a cycle is detected, an exception is thrown.
 *
 * Ties (directives with no dependency relationship) are broken alphabetically
 * for deterministic output.
 */
internal fun topologicalSortDirectives(directives: Collection<ViaductSchema.Directive>): List<ViaductSchema.Directive> {
    if (directives.isEmpty()) return emptyList()

    // Build name -> directive map for lookups
    val byName = directives.associateBy { it.name }

    // Color states for DFS
    val white = directives.map { it.name }.toMutableSet() // unvisited
    val gray = mutableSetOf<String>() // currently visiting (in stack)
    val black = mutableSetOf<String>() // finished

    // Get dependencies for a directive (names of directives applied to its arguments)
    fun getDependencies(directive: ViaductSchema.Directive): Set<String> =
        buildSet {
            for (arg in directive.args) {
                for (appliedDirective in arg.appliedDirectives) {
                    require(appliedDirective.name in byName) {
                        "Directive @${directive.name} references unknown directive @${appliedDirective.name} " +
                            "on argument '${arg.name}'. This indicates a malformed schema."
                    }
                    add(appliedDirective.name)
                }
            }
        }

    // Mutable path for cycle detection - uses backtracking to avoid O(n²) list copies
    val path = mutableListOf<String>()

    return buildList {
        // DFS visit function
        fun visit(name: String) {
            if (name in black) return
            if (name in gray) {
                // Cycle detected - build error message showing the cycle
                val cycleStart = path.indexOf(name)
                val cycle = path.subList(cycleStart, path.size) + name
                throw IllegalArgumentException(
                    "Circular directive dependency detected: ${cycle.joinToString(" -> ") { "@$it" }}. " +
                        "The GraphQL spec prohibits directives from referencing themselves directly or indirectly."
                )
            }

            white.remove(name)
            gray.add(name)
            path.add(name)

            val directive = byName[name]!!
            // Visit dependencies in alphabetical order for determinism
            for (dep in getDependencies(directive).sorted()) {
                visit(dep)
            }

            path.removeLast()
            gray.remove(name)
            black.add(name)

            // Add to final result
            add(directive)
        }

        // Visit all directives in alphabetical order for deterministic output.
        // Note: we check "name in white" because visit() may have already processed
        // this directive transitively as a dependency of an earlier directive.
        for (name in white.toList().sorted()) {
            if (name in white) {
                visit(name)
            }
        }
    }
}
