package viaduct.graphql.schema.binary

import graphql.language.NullValue
import viaduct.graphql.schema.ViaductSchema

/**
 * Decodes the root types and definitions sections.  The act of instantiating
 * this class mutates [result] as the definitions are decoded, so the
 * resulting state is stored there and not in the resulting instance.
 */
internal class DefinitionsDecoder(
    val data: BInputStream,
    private val identifiers: IdentifiersDecoder,
    private val types: TypeExpressionsDecoder,
    private val sourceLocations: SourceLocationsDecoder,
    private val constants: ConstantsDecoder,
    val result: BSchema,
) {
    init {
        // Read Root Types section
        data.validateMagicNumber(MAGIC_ROOT_TYPES, "root types")
        with(result) {
            queryTypeDef = lookupRootType(identifiers.getRootName(data.readInt()), "query")
            mutationTypeDef = lookupRootType(identifiers.getRootName(data.readInt()), "mutation")
            subscriptionTypeDef = lookupRootType(identifiers.getRootName(data.readInt()), "subscription")
        }

        // Read Definitions section
        // Each definition now starts with a name reference (identifier index)
        data.validateMagicNumber(MAGIC_DEFINITIONS, "definitions")
        for (defIndex in identifiers.indexedDefs.indices) {
            // Read name reference to find the definition
            val nameRef = data.readInt() and IDX_MASK
            val defName = identifiers.get(nameRef)
            val def = result.directives[defName] ?: result.types[defName]
                ?: throw IllegalArgumentException("Definition not found: $defName")

            when (def) {
                is BSchema.Directive -> {
                    // Read RefPlus and assign source location
                    val refPlus = DefinitionRefPlus(data.readInt())
                    def.sourceLocation = sourceLocations.get(refPlus.getIndex())

                    // Read directive info word
                    val directiveInfo = DirectiveInfo(data.readInt())
                    def.isRepeatable = directiveInfo.isRepeatable()
                    def.allowedLocations = directiveInfo.allowedLocations()

                    // Decode arguments if present
                    if (directiveInfo.hasArgs()) {
                        def.args = decodeInputLikeFieldList(def, BSchema::DirectiveArg)
                    }
                }

                is BSchema.Enum -> {
                    def.extensions = decodeExtensionList<BSchema.Enum, BSchema.EnumValue>(def) { ext, v ->
                        val valueRefPlus = EnumValueRefPlus(v)
                        BSchema.EnumValue(
                            ext,
                            identifiers.get(valueRefPlus.getIndex()), // name
                            decodeAppliedDirectives(valueRefPlus.hasAppliedDirectives())
                        )
                    }
                }

                is BSchema.Input -> {
                    def.extensions = decodeExtensionList(def) { ext, v ->
                        decodeFieldOrArg(ext, FieldRefPlus(v), BSchema::Field)
                    }
                }

                is BSchema.Interface -> {
                    def.extensions = decodeExtensionList(def) { ext, v ->
                        decodeFieldOrArg(ext, FieldRefPlus(v), BSchema::Field)
                    }

                    @Suppress("UNCHECKED_CAST")
                    def.possibleObjectTypes = (decodeTypeDefList() as List<BSchema.Object>).toSet()
                }

                is BSchema.Object -> {
                    def.extensions = decodeExtensionList(def) { ext, v ->
                        decodeFieldOrArg(ext, FieldRefPlus(v), BSchema::Field)
                    }

                    @Suppress("UNCHECKED_CAST")
                    def.unions = decodeTypeDefList() as List<BSchema.Union>
                }

                is BSchema.Scalar -> {
                    // Scalars always have exactly one extension
                    val refPlus = DefinitionRefPlus(data.readInt())
                    require(!refPlus.hasNext()) {
                        "Scalar type ${def.name} has multiple extensions (continuation bit not set)"
                    }

                    def.sourceLocation = sourceLocations.get(refPlus.getIndex())
                    def.appliedDirectives = decodeAppliedDirectives(refPlus.hasAppliedDirectives())
                }

                is BSchema.Union -> {
                    def.extensions = decodeExtensionList<BSchema.Union, BSchema.Object>(def) { _, v ->
                        typeDef<BSchema.Object>(v)
                    }
                }
            }
        }
    }

    //
    // Helper functions

    /**
     * Look up a root type (query/mutation/subscription) by name.
     * Returns null if name is null, otherwise validates that the type exists and is an Object.
     */
    private fun lookupRootType(
        name: String?,
        rootKind: String
    ): BSchema.Object? {
        if (name == null) return null
        val typeDef = result.types[name]
            ?: throw InvalidSchemaException("$rootKind root type '$name' not found")
        if (typeDef !is BSchema.Object) {
            throw InvalidSchemaException(
                "$rootKind root type '$name' must be an Object type, but got ${typeDef.javaClass.simpleName}"
            )
        }
        return typeDef
    }

    inline fun <reified T : BSchema.TypeDef> typeDef(refPlus: Int): T {
        val name = identifiers.get(refPlus)
        return result.types[name] as? T
            ?: throw IllegalArgumentException("Type not found or wrong type: $name")
    }

    /**
     * Return an empty list if _either_ [hasList] is false or
     * the first word is [EMPTY_LIST_MARKER].
     */
    fun decodeTypeDefList(hasList: Boolean = true): List<BSchema.TypeDef> {
        if (!hasList) return emptyList()
        var v = data.readInt()
        if (v == EMPTY_LIST_MARKER) {
            return emptyList()
        }
        return buildList {
            add(typeDef(v))
            while (!v.isBitSet(END_OF_LIST_BIT)) {
                v = data.readInt()
                add(typeDef(v))
            }
        }
    }

    fun decodeDefaultValue(hasDefaultValue: Boolean): Any? =
        when {
            hasDefaultValue -> constants.decodeConstant(data.readInt() and IDX_MASK)
            else -> null
        }

    /**
     * Decode a field or argument (works for both input-like and output fields).
     * Input-like fields (directive args, field args, input type fields) may have default values.
     * Output fields (interface/object fields) may have arguments.
     */
    fun <D, T> decodeFieldOrArg(
        container: D,
        refPlus: FieldRefPlus,
        create: (D, String, List<ViaductSchema.AppliedDirective>, BSchema.TypeExpr, Boolean, Any?) -> T,
    ): T {
        val result = create(
            container,
            identifiers.get(refPlus.getIndex()), // name
            decodeAppliedDirectives(refPlus.hasAppliedDirectives()),
            types.get(data.readInt()),
            refPlus.hasDefaultValue(),
            decodeDefaultValue(refPlus.hasDefaultValue()),
        )

        // Handle arguments for output fields (interface/object fields)
        if (refPlus.hasArguments() && result is BSchema.Field) {
            result.args = decodeInputLikeFieldList(result, BSchema::FieldArg)
        }

        return result
    }

    /**
     * Decode input-field-like elements, which are [Arg]s or [Field]s of
     * input types.
     *
     * Requires either that there's an EMPTY_LIST_MARKER marking the end of the
     * list _or_ that you know there's at least one element in the list.
     */
    fun <D, T> decodeInputLikeFieldList(
        container: D,
        create: (D, String, List<ViaductSchema.AppliedDirective>, BSchema.TypeExpr, Boolean, Any?) -> T,
    ): List<T> {
        var v = data.readInt()
        if (v == EMPTY_LIST_MARKER) return emptyList()

        return buildList {
            while (true) {
                val refPlus = FieldRefPlus(v)
                val field = decodeFieldOrArg(container, refPlus, create)
                add(field)
                if (!refPlus.hasNext()) break
                v = data.readInt()
            }
        }
    }

    /**
     * Decode applied directives, populating arguments using this logic:
     *
     * | Scenario                                                           | What arguments map contains
     * |--------------------------------------------------------------------|----------------------------
     * | Argument explicitly specified                                      | Specified value
     * | Argument not explicitly specified but has default in definition    | Default from definition
     * | Argument not explicitly specified, has no default, but is nullable | Null
     * | Other                                                              | Nothing, key not defined
     *
     * **Note on Missing Directive Definitions**: If the directive definition is not available
     * (which can happen for applied directives on directive definition arguments due to circular
     * dependencies), we use only the explicitly provided arguments. In this case, the encoder
     * ensures ALL arguments are encoded explicitly (no omission optimization).
     */
    fun decodeAppliedDirectives(hasAppliedDirectives: Boolean): List<ViaductSchema.AppliedDirective> {
        if (!hasAppliedDirectives) return emptyList()

        return buildList {
            var refPlus: AppliedDirectiveRefPlus
            do {
                refPlus = AppliedDirectiveRefPlus(data.readInt())
                val directiveName = identifiers.get(refPlus.getIndex())

                // Read explicitly provided arguments from the binary data
                val explicitArgs: Map<String, Any?> = if (refPlus.hasArguments()) {
                    buildMap {
                        var argRefPlus: AppliedDirectiveArgRefPlus
                        do {
                            argRefPlus = AppliedDirectiveArgRefPlus(data.readInt())
                            val argName = identifiers.get(argRefPlus.getIndex())
                            val constantRef = data.readInt() and IDX_MASK

                            val decoded = constants.decodeConstant(constantRef)
                            put(argName, if (decoded is NullValue) null else decoded)
                        } while (argRefPlus.hasNext())
                    }
                } else {
                    emptyMap()
                }

                // Build final arguments map using directive definition for defaults.
                // If the directive definition is not yet available (can happen with circular
                // directive dependencies), we use only the explicit args. The encoder ensures
                // that in such cases, ALL arguments are encoded explicitly.
                val directiveDef = result.directives[directiveName]
                val finalArgs = if (directiveDef != null && directiveDef.args.isNotEmpty()) {
                    buildMap {
                        for (argDef in directiveDef.args) {
                            when {
                                // Argument explicitly specified
                                argDef.name in explicitArgs -> put(argDef.name, explicitArgs[argDef.name])
                                // Argument has default in definition
                                argDef.hasDefault -> put(argDef.name, argDef.defaultValue)
                                // Argument is nullable (no default)
                                argDef.type.isNullable -> put(argDef.name, null)
                                // Other: key not defined (don't add to map)
                            }
                        }
                    }
                } else {
                    // Directive definition not available (or has no args).
                    // Use explicit args directly. This handles circular directive dependencies
                    // where @A's arg has @B applied and @B's arg has @A applied.
                    explicitArgs
                }

                add(ViaductSchema.AppliedDirective.of(directiveName, finalArgs))
            } while (refPlus.hasNext())
        }
    }

    fun <D : BSchema.TypeDef, M : BSchema.Def> decodeExtensionList(
        def: D,
        block: (BSchema.Extension<D, M>, Int) -> M
    ): List<BSchema.Extension<D, M>> =
        buildList {
            var isBase = true
            do {
                val refPlus = DefinitionRefPlus(data.readInt())

                @Suppress("UNCHECKED_CAST")
                val ext = BSchema.Extension<D, M>(
                    def,
                    decodeAppliedDirectives(refPlus.hasAppliedDirectives()),
                    sourceLocations.get(refPlus.getIndex()),
                    isBase,
                    supers = decodeTypeDefList(refPlus.hasImplementedTypes()) as List<BSchema.Interface>,
                )
                add(ext)

                var v = data.readInt()
                if (v != EMPTY_LIST_MARKER) {
                    ext.members = buildList {
                        do {
                            add(block(ext, v))
                            // NOTE: we're assuming END_OF_LIST_BIT is used by
                            // all InputLikeField cases
                            val hasNext = (0 == (v and END_OF_LIST_BIT))
                            if (hasNext) {
                                v = data.readInt()
                            }
                        } while (hasNext)
                    }
                }
                isBase = false
            } while (refPlus.hasNext())
        }
}
