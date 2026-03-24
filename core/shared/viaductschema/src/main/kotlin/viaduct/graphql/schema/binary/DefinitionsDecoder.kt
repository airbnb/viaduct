package viaduct.graphql.schema.binary

import viaduct.graphql.schema.InvalidSchemaException
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema

/**
 * Decodes the root types and definitions sections.
 *
 * @param queryTypeDef The query root type
 * @param mutationTypeDef The mutation root type (may be null)
 * @param subscriptionTypeDef The subscription root type (may be null)
 */
internal class DefinitionsDecoder(
    val data: BInputStream,
    private val identifiers: IdentifiersDecoder,
    private val types: TypeExpressionsDecoder,
    private val sourceLocations: SourceLocationsDecoder,
    private val constants: ConstantsDecoder,
) {
    val queryTypeDef: SchemaWithData.Object?
    val mutationTypeDef: SchemaWithData.Object?
    val subscriptionTypeDef: SchemaWithData.Object?

    init {
        // Read Root Types section
        data.validateMagicNumber(MAGIC_ROOT_TYPES, "root types")
        queryTypeDef = lookupRootType(identifiers.getRootName(data.readInt()), "query")
        mutationTypeDef = lookupRootType(identifiers.getRootName(data.readInt()), "mutation")
        subscriptionTypeDef = lookupRootType(identifiers.getRootName(data.readInt()), "subscription")

        // Read Definitions section
        data.validateMagicNumber(MAGIC_DEFINITIONS, "definitions")
        for (defIndex in identifiers.indexedDefs.indices) {
            // Read name reference to find the definition
            val nameRef = data.readInt() and IDX_MASK
            val defName = identifiers.get(nameRef)
            val def = identifiers.directives[defName] ?: identifiers.types[defName]
                ?: throw IllegalArgumentException("Definition not found: $defName")

            when (def) {
                is SchemaWithData.Directive -> {
                    // Read RefPlus and source location
                    val refPlus = DefinitionRefPlus(data.readInt())
                    val sourceLocation = sourceLocations.get(refPlus.getIndex())

                    // Read directive info word
                    val directiveInfo = DirectiveInfo(data.readInt())

                    // Decode arguments if present
                    val args = if (directiveInfo.hasArgs()) {
                        decodeInputLikeFieldList(def, SchemaWithData::DirectiveArg)
                    } else {
                        emptyList()
                    }

                    def.populate(
                        directiveInfo.isRepeatable(),
                        directiveInfo.allowedLocations(),
                        sourceLocation,
                        args
                    )
                }

                is SchemaWithData.Enum -> {
                    def.populate(
                        decodeExtensionList<SchemaWithData.Enum, SchemaWithData.EnumValue>(def) { ext, v ->
                            val valueRefPlus = EnumValueRefPlus(v)
                            SchemaWithData.EnumValue(
                                ext,
                                identifiers.get(valueRefPlus.getIndex()), // name
                                decodeAppliedDirectives(valueRefPlus.hasAppliedDirectives())
                            )
                        }
                    )
                }

                is SchemaWithData.Input -> {
                    def.populate(
                        decodeExtensionList(def) { ext, v ->
                            decodeFieldOrArg(ext, FieldRefPlus(v), SchemaWithData::Field)
                        }
                    )
                }

                is SchemaWithData.Interface -> {
                    @Suppress("UNCHECKED_CAST")
                    def.populate(
                        decodeExtensionListWithSupers(def) { ext, v ->
                            decodeOutputField(ext, FieldRefPlus(v))
                        },
                        (decodeTypeDefList() as List<SchemaWithData.Object>).toSet()
                    )
                }

                is SchemaWithData.Object -> {
                    @Suppress("UNCHECKED_CAST")
                    def.populate(
                        decodeExtensionListWithSupers(def) { ext, v ->
                            decodeOutputField(ext, FieldRefPlus(v))
                        },
                        decodeTypeDefList() as List<SchemaWithData.Union>
                    )
                }

                is SchemaWithData.Scalar -> {
                    def.populate(decodeScalarExtensionList(def))
                }

                is SchemaWithData.Union -> {
                    def.populate(
                        decodeExtensionList<SchemaWithData.Union, SchemaWithData.Object>(def) { _, v ->
                            typeDef<SchemaWithData.Object>(v)
                        }
                    )
                }
            }
        }

        // Phase 2: Validate applied directives now that all definitions are populated
        validateAllAppliedDirectives()
    }

    /**
     * Validates that all applied directives reference existing directive definitions
     * and that their arguments match the definition.
     */
    private fun validateAllAppliedDirectives() {
        val directives = identifiers.directives
        for (typeDef in identifiers.types.values) {
            directives.validateAppliedDirectives(typeDef)
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
    ): SchemaWithData.Object? {
        if (name == null) return null
        val typeDef = identifiers.types[name]
            ?: throw InvalidSchemaException("$rootKind root type '$name' not found")
        if (typeDef !is SchemaWithData.Object) {
            throw InvalidSchemaException(
                "$rootKind root type '$name' must be an Object type, but got ${typeDef.javaClass.simpleName}"
            )
        }
        return typeDef
    }

    inline fun <reified T : SchemaWithData.TypeDef> typeDef(refPlus: Int): T {
        val name = identifiers.get(refPlus)
        return identifiers.types[name] as? T
            ?: throw IllegalArgumentException("Type not found or wrong type: $name")
    }

    /**
     * Return an empty list if _either_ [hasList] is false or
     * the first word is [EMPTY_LIST_MARKER].
     */
    fun decodeTypeDefList(hasList: Boolean = true): List<SchemaWithData.TypeDef> {
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

    fun decodeDefaultValue(hasDefaultValue: Boolean): ViaductSchema.Literal? =
        if (hasDefaultValue) {
            constants.decodeConstant(data.readInt() and IDX_MASK)
        } else {
            null
        }

    /**
     * Decode a field or argument (works for both input-like and output fields).
     * Input-like fields (directive args, field args, input type fields) may have default values.
     * Output fields (interface/object fields) may have arguments - use [decodeOutputField] for those.
     */
    fun <D, T> decodeFieldOrArg(
        container: D,
        refPlus: FieldRefPlus,
        create: (D, String, ViaductSchema.TypeExpr<SchemaWithData.TypeDef>, List<ViaductSchema.AppliedDirective<*>>, Boolean, ViaductSchema.Literal?) -> T,
    ): T {
        // Read in binary format order: name, appliedDirectives, type, hasDefault, defaultValue
        val name = identifiers.get(refPlus.getIndex())
        val appliedDirectives = decodeAppliedDirectives(refPlus.hasAppliedDirectives())
        val type = types.get(data.readInt())
        val hasDefault = refPlus.hasDefaultValue()
        val defaultValue = decodeDefaultValue(hasDefault)
        // Pass to constructor in standardized order: container, name, type, appliedDirectives, hasDefault, defaultValue
        return create(container, name, type, appliedDirectives, hasDefault, defaultValue)
    }

    /**
     * Decode an output field (interface/object field) which may have arguments.
     */
    fun decodeOutputField(
        container: ViaductSchema.Extension<SchemaWithData.Record, SchemaWithData.Field>,
        refPlus: FieldRefPlus,
    ): SchemaWithData.Field {
        // Read in binary format order: name, appliedDirectives, type, hasDefault, defaultValue
        val name = identifiers.get(refPlus.getIndex())
        val appliedDirectives = decodeAppliedDirectives(refPlus.hasAppliedDirectives())
        val type = types.get(data.readInt())
        val hasDefault = refPlus.hasDefaultValue()
        val defaultValue = decodeDefaultValue(hasDefault)
        val hasArgs = refPlus.hasArguments()

        // Pass to constructor in standardized order: container, name, type, appliedDirectives, hasDefault, defaultValue
        return SchemaWithData.Field(
            container,
            name,
            type,
            appliedDirectives,
            hasDefault,
            defaultValue,
            argsFactory = { field ->
                if (hasArgs) {
                    decodeInputLikeFieldList(field, SchemaWithData::FieldArg)
                } else {
                    emptyList()
                }
            }
        )
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
        create: (D, String, ViaductSchema.TypeExpr<SchemaWithData.TypeDef>, List<ViaductSchema.AppliedDirective<*>>, Boolean, ViaductSchema.Literal?) -> T,
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
     * | Argument not explicitly specified, has no default, but is nullable | NullValue
     * | Other                                                              | Nothing, key not defined
     *
     * Directives are encoded in topological order (dependencies first), so the directive
     * definition is always available when decoding applied directives.
     */
    fun decodeAppliedDirectives(hasAppliedDirectives: Boolean): List<ViaductSchema.AppliedDirective<*>> {
        if (!hasAppliedDirectives) return emptyList()

        return buildList {
            var refPlus: AppliedDirectiveRefPlus
            do {
                refPlus = AppliedDirectiveRefPlus(data.readInt())
                val directiveName = identifiers.get(refPlus.getIndex())

                // Read explicitly provided arguments from the binary data
                val explicitArgs: Map<String, ViaductSchema.Literal> = if (refPlus.hasArguments()) {
                    buildMap {
                        var argRefPlus: AppliedDirectiveArgRefPlus
                        do {
                            argRefPlus = AppliedDirectiveArgRefPlus(data.readInt())
                            val argName = identifiers.get(argRefPlus.getIndex())
                            val constantRef = data.readInt() and IDX_MASK

                            val decoded = constants.decodeConstant(constantRef)
                            put(argName, decoded)
                        } while (argRefPlus.hasNext())
                    }
                } else {
                    emptyMap()
                }

                // Build final arguments map using directive definition for defaults.
                // Directive definitions should always be available because directives are encoded
                // in topological order (dependencies first).
                val directiveDef = identifiers.directives[directiveName]
                    ?: throw InvalidFileFormatException(
                        "Directive definition not found for @$directiveName. " +
                            "This indicates a malformed binary file."
                    )

                val finalArgs: Map<String, ViaductSchema.Literal> = if (directiveDef.args.isNotEmpty()) {
                    buildMap {
                        for (argDef in directiveDef.args) {
                            when {
                                // Argument explicitly specified
                                argDef.name in explicitArgs -> put(argDef.name, explicitArgs[argDef.name]!!)
                                // Argument has default in definition
                                argDef.hasDefault -> put(argDef.name, argDef.defaultValue)
                                // Argument is nullable (no default)
                                argDef.type.isNullable -> put(argDef.name, ViaductSchema.NULL)
                                // Other: key not defined (don't add to map)
                            }
                        }
                    }
                } else {
                    explicitArgs
                }

                add(ViaductSchema.AppliedDirective.of(directiveDef, finalArgs))
            } while (refPlus.hasNext())
        }
    }

    fun decodeScalarExtensionList(def: SchemaWithData.Scalar): List<ViaductSchema.Extension<SchemaWithData.Scalar, Nothing>> =
        buildList {
            var isBase = true
            do {
                val refPlus = DefinitionRefPlus(data.readInt())
                val appliedDirectives = decodeAppliedDirectives(refPlus.hasAppliedDirectives())
                val sourceLocation = sourceLocations.get(refPlus.getIndex())

                @Suppress("UNCHECKED_CAST")
                val ext = ViaductSchema.Extension.of(
                    def = def,
                    memberFactory = { emptyList<Nothing>() },
                    isBase = isBase,
                    appliedDirectives = appliedDirectives,
                    sourceLocation = sourceLocation
                )
                add(ext)
                isBase = false
            } while (refPlus.hasNext())
        }

    fun <D : SchemaWithData.TypeDef, M : SchemaWithData.Def> decodeExtensionList(
        def: D,
        block: (ViaductSchema.Extension<D, M>, Int) -> M
    ): List<ViaductSchema.Extension<D, M>> =
        buildList {
            var isBase = true
            do {
                val refPlus = DefinitionRefPlus(data.readInt())
                val appliedDirectives = decodeAppliedDirectives(refPlus.hasAppliedDirectives())
                val sourceLocation = sourceLocations.get(refPlus.getIndex())

                @Suppress("UNCHECKED_CAST")
                val ext = ViaductSchema.Extension.of(
                    def = def,
                    memberFactory = { ext ->
                        var v = data.readInt()
                        if (v == EMPTY_LIST_MARKER) {
                            emptyList()
                        } else {
                            buildList {
                                do {
                                    add(block(ext as ViaductSchema.Extension<D, M>, v))
                                    val hasNext = (0 == (v and END_OF_LIST_BIT))
                                    if (hasNext) {
                                        v = data.readInt()
                                    }
                                } while (hasNext)
                            }
                        }
                    },
                    isBase = isBase,
                    appliedDirectives = appliedDirectives,
                    sourceLocation = sourceLocation
                ) as ViaductSchema.Extension<D, M>
                add(ext)
                isBase = false
            } while (refPlus.hasNext())
        }

    fun <D : SchemaWithData.TypeDef, M : SchemaWithData.Def> decodeExtensionListWithSupers(
        def: D,
        block: (ViaductSchema.Extension<D, M>, Int) -> M
    ): List<ViaductSchema.ExtensionWithSupers<D, M>> =
        buildList {
            var isBase = true
            do {
                val refPlus = DefinitionRefPlus(data.readInt())
                val appliedDirectives = decodeAppliedDirectives(refPlus.hasAppliedDirectives())
                val sourceLocation = sourceLocations.get(refPlus.getIndex())

                @Suppress("UNCHECKED_CAST")
                val supers = decodeTypeDefList(refPlus.hasImplementedTypes()) as List<SchemaWithData.Interface>

                // Validate that all supers are actually Interface types before creating extension
                for (superType in supers) {
                    @Suppress("USELESS_IS_CHECK") // Defensive check for unchecked cast above
                    if (superType !is SchemaWithData.Interface) {
                        val typeName = superType.name
                        throw InvalidSchemaException(
                            "Type ${def.name} implements $typeName which is not an Interface type " +
                                "(got ${superType.javaClass.simpleName})"
                        )
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val ext = ViaductSchema.ExtensionWithSupers.of(
                    def = def,
                    memberFactory = { ext ->
                        var v = data.readInt()
                        if (v == EMPTY_LIST_MARKER) {
                            emptyList()
                        } else {
                            buildList {
                                do {
                                    add(block(ext as ViaductSchema.Extension<D, M>, v))
                                    val hasNext = (0 == (v and END_OF_LIST_BIT))
                                    if (hasNext) {
                                        v = data.readInt()
                                    }
                                } while (hasNext)
                            }
                        }
                    },
                    isBase = isBase,
                    appliedDirectives = appliedDirectives,
                    supers = supers,
                    sourceLocation = sourceLocation
                ) as ViaductSchema.ExtensionWithSupers<D, M>
                add(ext)
                isBase = false
            } while (refPlus.hasNext())
        }
}

//
// Applied directive validation helpers
//

/**
 * Validates that all applied directives reference existing directive definitions
 * and that their arguments match the definition.
 */
private fun Map<String, SchemaWithData.Directive>.validateAppliedDirectives(
    appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>,
    context: String
) {
    for (applied in appliedDirectives) {
        val definition = this[applied.name]
            ?: throw InvalidSchemaException(
                "Applied directive @${applied.name} on $context references non-existent directive definition"
            )
        // Validate that all arguments in applied directive exist in definition
        for (argName in applied.arguments.keys) {
            if (definition.args.none { it.name == argName }) {
                throw InvalidSchemaException(
                    "Applied directive @${applied.name} on $context has argument '$argName' " +
                        "not defined in directive definition"
                )
            }
        }
    }
}

/**
 * Validates all applied directives on a type definition (including its extensions and members).
 */
internal fun Map<String, SchemaWithData.Directive>.validateAppliedDirectives(typeDef: SchemaWithData.TypeDef) {
    when (typeDef) {
        is SchemaWithData.Scalar -> {
            validateAppliedDirectives(typeDef.appliedDirectives, "scalar ${typeDef.name}")
        }
        is SchemaWithData.Enum -> {
            for (ext in typeDef.extensions) {
                validateAppliedDirectives(ext.appliedDirectives, "type ${typeDef.name}")
                for (member in ext.members) {
                    validateAppliedDirectives(member.appliedDirectives, member.describe())
                }
            }
        }
        is SchemaWithData.Union -> {
            for (ext in typeDef.extensions) {
                validateAppliedDirectives(ext.appliedDirectives, "type ${typeDef.name}")
            }
        }
        is SchemaWithData.Interface -> {
            for (ext in typeDef.extensions) {
                validateAppliedDirectives(ext.appliedDirectives, "type ${typeDef.name}")
                for (member in ext.members) {
                    validateAppliedDirectives(member.appliedDirectives, member.describe())
                }
            }
        }
        is SchemaWithData.Input -> {
            for (ext in typeDef.extensions) {
                validateAppliedDirectives(ext.appliedDirectives, "type ${typeDef.name}")
                for (member in ext.members) {
                    validateAppliedDirectives(member.appliedDirectives, member.describe())
                }
            }
        }
        is SchemaWithData.Object -> {
            for (ext in typeDef.extensions) {
                validateAppliedDirectives(ext.appliedDirectives, "type ${typeDef.name}")
                for (member in ext.members) {
                    validateAppliedDirectives(member.appliedDirectives, member.describe())
                }
            }
        }
    }
}
