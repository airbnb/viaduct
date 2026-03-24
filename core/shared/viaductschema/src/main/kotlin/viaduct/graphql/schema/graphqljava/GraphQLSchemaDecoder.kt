package viaduct.graphql.schema.graphqljava

import graphql.language.Directive
import graphql.language.Type
import graphql.language.TypeName
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.BitVector

/**
 * Transforms graphql-java GraphQLSchema elements into ViaductSchema elements.
 * This class centralizes all transformation logic, separating it from the
 * populate() methods in TypeDef classes.
 *
 * Analogous to TypeDefinitionRegistryDecoder for GJSchemaRaw.
 */
internal class GraphQLSchemaDecoder(
    private val schema: GraphQLSchema,
    private val types: Map<String, SchemaWithData.TypeDef>,
    private val directives: Map<String, SchemaWithData.Directive>,
) {
    // Cache for union membership and interface implementors (computed once)
    private val unionMembership: Map<String, List<SchemaWithData.Union>> by lazy {
        buildMap<String, MutableList<SchemaWithData.Union>> {
            types.values.filterIsInstance<SchemaWithData.Union>().forEach { union ->
                union.gjDef.types.forEach { memberType ->
                    getOrPut(memberType.name) { mutableListOf() }.add(union)
                }
            }
        }
    }

    private val interfaceImplementors: Map<String, List<GraphQLNamedType>> by lazy {
        buildMap<String, MutableList<GraphQLNamedType>> {
            schema.allTypesAsList.filterIsInstance<GraphQLImplementingType>().forEach { implType ->
                implType.interfaces.forEach { iface ->
                    getOrPut(iface.name) { mutableListOf() }.add(implType)
                }
            }
        }
    }

    // ========== Core Decoding Primitives ==========

    fun decodeTypeExpr(gtype: GraphQLType): ViaductSchema.TypeExpr<SchemaWithData.TypeDef> {
        var baseTypeNullable = true
        var listNullable = ViaductSchema.TypeExpr.NO_WRAPPERS

        var t = gtype
        if (GraphQLTypeUtil.isWrapped(t)) {
            val wrapperBuilder = BitVector.Builder()
            do {
                if (GraphQLTypeUtil.isList(t)) {
                    wrapperBuilder.add(1L, 1)
                    t = GraphQLTypeUtil.unwrapOne(t)
                } else if (GraphQLTypeUtil.isNonNull(t)) {
                    t = GraphQLTypeUtil.unwrapOne(t)
                    if (GraphQLTypeUtil.isList(t)) {
                        wrapperBuilder.add(0L, 1)
                        t = GraphQLTypeUtil.unwrapOne(t)
                    } else if (GraphQLTypeUtil.isWrapped(t)) {
                        throw IllegalStateException("Unexpected GraphQL wrapping $gtype.")
                    } else {
                        baseTypeNullable = false
                    }
                } else {
                    throw IllegalStateException("Unexpected GraphQL wrapper $gtype.")
                }
            } while (GraphQLTypeUtil.isWrapped(t))
            listNullable = wrapperBuilder.build()
        }

        val baseTypeDefName = GraphQLTypeUtil.unwrapAll(gtype).name
        val baseTypeDef = types[baseTypeDefName]
            ?: error("Type not found: $baseTypeDefName")
        return ViaductSchema.TypeExpr(baseTypeDef, baseTypeNullable, listNullable)
    }

    private fun decodeTypeExprFromLang(type: Type<*>): ViaductSchema.TypeExpr<SchemaWithData.TypeDef> =
        type.toTypeExpr { baseTypeDefName, baseTypeNullable, listNullable ->
            val baseTypeDef = types[baseTypeDefName]
                ?: error("Type not found: $baseTypeDefName")
            ViaductSchema.TypeExpr(baseTypeDef, baseTypeNullable, listNullable)
        }

    fun decodeSourceLocation(def: graphql.language.Node<*>?): ViaductSchema.SourceLocation? = def?.sourceLocation?.sourceName?.let { ViaductSchema.SourceLocation(it) }

    fun decodeAppliedDirectives(container: GraphQLDirectiveContainer): List<ViaductSchema.AppliedDirective<*>> =
        container.appliedDirectives.map { appliedDirective ->
            val gjDirectiveDef = schema.getDirective(appliedDirective.name)
                ?: error("Directive @${appliedDirective.name} not found in schema.")
            val directiveDef = directives[appliedDirective.name]
                ?: error("Directive @${appliedDirective.name} not found in directives map.")
            ViaductSchema.AppliedDirective.of(
                directive = directiveDef,
                arguments = gjDirectiveDef.arguments.associate { argDef ->
                    argDef.name to decodeAppliedDirectiveArg(appliedDirective, argDef)
                }
            )
        }

    private fun decodeAppliedDirectiveArg(
        appliedDirective: GraphQLAppliedDirective,
        argDef: GraphQLArgument
    ): ViaductSchema.Literal {
        val appliedArg = appliedDirective.getArgument(argDef.name)
        val type = decodeTypeExpr(argDef.type)
        val convertedValue = when {
            appliedArg != null -> ValueConverter.convert(type, appliedArg.argumentValue)
            argDef.hasSetDefaultValue() -> ValueConverter.convert(type, argDef.argumentDefaultValue)
            else -> null
        }
        return convertedValue ?: ViaductSchema.NULL.also {
            require(type.isNullable) {
                "No value for non-nullable argument ${argDef.name} on @${appliedDirective.name}"
            }
        }
    }

    private fun decodeAppliedDirectivesFromLang(langDirectives: List<Directive>): List<ViaductSchema.AppliedDirective<*>> =
        langDirectives.map { dir ->
            val def = schema.getDirective(dir.name)?.definition
                ?: error("Directive @${dir.name} not found in schema.")
            val directiveDef = directives[dir.name]
                ?: error("Directive @${dir.name} not found in directives map.")
            dir.toAppliedDirective(def, directiveDef) { decodeTypeExprFromLang(it) }
        }

    fun decodeHasDefault(arg: GraphQLArgument): Boolean = arg.hasSetDefaultValue()

    fun decodeDefaultValue(arg: GraphQLArgument): ViaductSchema.Literal? =
        if (arg.hasSetDefaultValue()) {
            ValueConverter.convert(decodeTypeExpr(arg.type), arg.argumentDefaultValue)
        } else {
            null
        }

    // ========== Cross-Reference Computation ==========

    fun computeUnions(objectDef: SchemaWithData.Object): List<SchemaWithData.Union> = unionMembership[objectDef.name] ?: emptyList()

    fun computePossibleObjectTypes(interfaceDef: SchemaWithData.Interface): Set<SchemaWithData.Object> {
        val result = mutableSetOf<SchemaWithData.Object>()

        fun collectObjectTypes(implType: GraphQLNamedType) {
            if (implType.name == interfaceDef.name) {
                throw IllegalArgumentException("Cyclical inheritance.")
            }
            when (implType) {
                is GraphQLObjectType -> result.add(types[implType.name] as SchemaWithData.Object)
                is GraphQLInterfaceType -> {
                    interfaceImplementors[implType.name]?.forEach { collectObjectTypes(it) }
                }
            }
        }

        interfaceImplementors[interfaceDef.name]?.forEach { collectObjectTypes(it) }
        return result
    }

    // ========== Scalar ==========

    fun createScalarExtensions(scalarDef: SchemaWithData.Scalar): List<ViaductSchema.Extension<SchemaWithData.Scalar, Nothing>> {
        val gjDef = scalarDef.gjDef
        // GraphQLScalarType from UnExecutableSchemaGenerator loses extension definitions
        // (extensionDefinitions is always empty), but it merges all directives from both
        // base and extensions into the scalar's appliedDirectives. So we create extensions
        // from the language-level definitions if available, falling back to merged directives.
        val langDefs = listOf(gjDef.definition) + gjDef.extensionDefinitions
        return if (langDefs.all { it == null } || (gjDef.extensionDefinitions.isEmpty() && gjDef.definition == null)) {
            // No language definitions available - create single extension with merged directives
            listOf(
                ViaductSchema.Extension.of(
                    def = scalarDef,
                    memberFactory = { _ -> emptyList() },
                    isBase = true,
                    appliedDirectives = decodeAppliedDirectives(gjDef),
                    sourceLocation = null
                )
            )
        } else {
            // Have language definitions - but graphql-java loses extension definitions for scalars
            // So we only have the base definition. Use its directives from the language AST,
            // but get extension directives from the merged appliedDirectives.
            val baseDirectives = gjDef.definition?.let { decodeAppliedDirectivesFromLang(it.directives) } ?: emptyList()
            val allDirectives = decodeAppliedDirectives(gjDef)
            // Extension directives are all directives that aren't in the base
            val baseDirectiveSet = baseDirectives.map { it.name to it.arguments }.toSet()
            val extensionDirectives = allDirectives.filterNot { (it.name to it.arguments) in baseDirectiveSet }

            buildList {
                // Base extension
                add(
                    ViaductSchema.Extension.of(
                        def = scalarDef,
                        memberFactory = { _ -> emptyList() },
                        isBase = true,
                        appliedDirectives = baseDirectives,
                        sourceLocation = decodeSourceLocation(gjDef.definition)
                    )
                )
                // If there are extension directives, create a synthetic extension for them
                if (extensionDirectives.isNotEmpty()) {
                    add(
                        ViaductSchema.Extension.of(
                            def = scalarDef,
                            memberFactory = { _ -> emptyList() },
                            isBase = false,
                            appliedDirectives = extensionDirectives,
                            sourceLocation = null
                        )
                    )
                }
            }
        }
    }

    // ========== Enum ==========

    fun createEnumExtensions(enumDef: SchemaWithData.Enum): List<ViaductSchema.Extension<SchemaWithData.Enum, SchemaWithData.EnumValue>> {
        val gjDef = enumDef.gjDef
        return (listOf(gjDef.definition) + gjDef.extensionDefinitions).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = enumDef,
                memberFactory = { containingExtension ->
                    if (gjLangTypeDef == null) {
                        gjDef.values.map { evDef ->
                            createEnumValue(evDef, containingExtension, decodeAppliedDirectives(evDef))
                        }
                    } else {
                        gjLangTypeDef.enumValueDefinitions.map { evd ->
                            val evDef = gjDef.getValue(evd.name)
                                ?: error("Enum value ${evd.name} not found in ${gjDef.name}")
                            createEnumValue(
                                evDef,
                                containingExtension,
                                decodeAppliedDirectivesFromLang(evd.directives)
                            )
                        }
                    }
                },
                isBase = gjLangTypeDef == gjDef.definition,
                appliedDirectives = if (gjLangTypeDef != null) {
                    decodeAppliedDirectivesFromLang(gjLangTypeDef.directives)
                } else {
                    decodeAppliedDirectives(gjDef)
                },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    private fun createEnumValue(
        evDef: GraphQLEnumValueDefinition,
        containingExtension: ViaductSchema.Extension<SchemaWithData.Enum, SchemaWithData.EnumValue>,
        appliedDirectives: List<ViaductSchema.AppliedDirective<*>>
    ) = SchemaWithData.EnumValue(containingExtension, evDef.name, appliedDirectives, evDef)

    // ========== Input ==========

    fun createInputExtensions(inputDef: SchemaWithData.Input): List<ViaductSchema.Extension<SchemaWithData.Input, SchemaWithData.Field>> {
        val gjDef = inputDef.gjDef
        return (listOf(gjDef.definition) + gjDef.extensionDefinitions).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = inputDef,
                memberFactory = { containingExtension ->
                    if (gjLangTypeDef == null) {
                        gjDef.fields.map { fieldDef ->
                            createInputField(fieldDef, containingExtension, decodeAppliedDirectives(fieldDef))
                        }
                    } else {
                        gjLangTypeDef.inputValueDefinitions.map { ivd ->
                            val fieldDef = gjDef.getField(ivd.name)
                                ?: error("Field ${ivd.name} not found in ${gjDef.name}")
                            createInputField(
                                fieldDef,
                                containingExtension,
                                decodeAppliedDirectivesFromLang(ivd.directives)
                            )
                        }
                    }
                },
                isBase = gjLangTypeDef == gjDef.definition,
                appliedDirectives = if (gjLangTypeDef != null) {
                    decodeAppliedDirectivesFromLang(gjLangTypeDef.directives)
                } else {
                    decodeAppliedDirectives(gjDef)
                },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    private fun createInputField(
        fieldDef: GraphQLInputObjectField,
        containingExtension: ViaductSchema.Extension<SchemaWithData.Input, SchemaWithData.Field>,
        appliedDirectives: List<ViaductSchema.AppliedDirective<*>>
    ): SchemaWithData.Field {
        val hasDefault = fieldDef.hasSetDefaultValue()
        val defaultValue = if (hasDefault) {
            ValueConverter.convert(decodeTypeExpr(fieldDef.type), fieldDef.inputFieldDefaultValue)
        } else {
            null
        }
        @Suppress("UNCHECKED_CAST")
        return SchemaWithData.Field(
            containingExtension as ViaductSchema.Extension<SchemaWithData.Record, SchemaWithData.Field>,
            fieldDef.name,
            decodeTypeExpr(fieldDef.type),
            appliedDirectives,
            hasDefault,
            defaultValue,
            fieldDef, // data is GraphQLInputObjectField
        )
    }

    // ========== Interface ==========

    fun createInterfaceExtensions(interfaceDef: SchemaWithData.Interface): List<ViaductSchema.ExtensionWithSupers<SchemaWithData.Interface, SchemaWithData.Field>> {
        val gjDef = interfaceDef.gjDef
        return (listOf(gjDef.definition) + gjDef.extensionDefinitions).map { gjLangTypeDef ->
            ViaductSchema.ExtensionWithSupers.of(
                def = interfaceDef,
                memberFactory = { containingExtension ->
                    if (gjLangTypeDef == null) {
                        gjDef.fields
                            .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                            .map { fieldDef ->
                                @Suppress("UNCHECKED_CAST")
                                createOutputField(
                                    fieldDef,
                                    containingExtension as ViaductSchema.Extension<SchemaWithData.Record, SchemaWithData.Field>,
                                    decodeAppliedDirectives(fieldDef)
                                )
                            }
                    } else {
                        gjLangTypeDef.fieldDefinitions
                            .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                            .map { fd ->
                                val fieldDef = gjDef.getFieldDefinition(fd.name)
                                    ?: error("Field ${fd.name} not found in ${gjDef.name}")
                                @Suppress("UNCHECKED_CAST")
                                createOutputField(
                                    fieldDef,
                                    containingExtension as ViaductSchema.Extension<SchemaWithData.Record, SchemaWithData.Field>,
                                    decodeAppliedDirectivesFromLang(fd.directives)
                                )
                            }
                    }
                },
                isBase = gjLangTypeDef == gjDef.definition,
                appliedDirectives = if (gjLangTypeDef != null) {
                    decodeAppliedDirectivesFromLang(gjLangTypeDef.directives)
                } else {
                    decodeAppliedDirectives(gjDef)
                },
                supers = if (gjLangTypeDef == null) {
                    gjDef.interfaces.map { types[it.name] as SchemaWithData.Interface }
                } else {
                    gjLangTypeDef.implements.map { types[(it as TypeName).name] as SchemaWithData.Interface }
                },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Object ==========

    fun createObjectExtensions(objectDef: SchemaWithData.Object): List<ViaductSchema.ExtensionWithSupers<SchemaWithData.Object, SchemaWithData.Field>> {
        val gjDef = objectDef.gjDef
        return (listOf(gjDef.definition) + gjDef.extensionDefinitions).map { gjLangTypeDef ->
            ViaductSchema.ExtensionWithSupers.of(
                def = objectDef,
                memberFactory = { containingExtension ->
                    if (gjLangTypeDef == null) {
                        gjDef.fields
                            .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                            .map { fieldDef ->
                                @Suppress("UNCHECKED_CAST")
                                createOutputField(
                                    fieldDef,
                                    containingExtension as ViaductSchema.Extension<SchemaWithData.Record, SchemaWithData.Field>,
                                    decodeAppliedDirectives(fieldDef)
                                )
                            }
                    } else {
                        gjLangTypeDef.fieldDefinitions
                            .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                            .map { fd ->
                                val fieldDef = gjDef.getFieldDefinition(fd.name)
                                    ?: error("Field ${fd.name} not found in ${gjDef.name}")
                                @Suppress("UNCHECKED_CAST")
                                createOutputField(
                                    fieldDef,
                                    containingExtension as ViaductSchema.Extension<SchemaWithData.Record, SchemaWithData.Field>,
                                    decodeAppliedDirectivesFromLang(fd.directives)
                                )
                            }
                    }
                },
                isBase = gjLangTypeDef == gjDef.definition,
                appliedDirectives = if (gjLangTypeDef != null) {
                    decodeAppliedDirectivesFromLang(gjLangTypeDef.directives)
                } else {
                    decodeAppliedDirectives(gjDef)
                },
                supers = if (gjLangTypeDef == null) {
                    gjDef.interfaces.map { types[it.name] as SchemaWithData.Interface }
                } else {
                    gjLangTypeDef.implements.map { types[(it as TypeName).name] as SchemaWithData.Interface }
                },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Union ==========

    fun createUnionExtensions(unionDef: SchemaWithData.Union): List<ViaductSchema.Extension<SchemaWithData.Union, SchemaWithData.Object>> {
        val gjDef = unionDef.gjDef
        return (listOf(gjDef.definition) + gjDef.extensionDefinitions).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = unionDef,
                memberFactory = { _ ->
                    if (gjLangTypeDef == null) {
                        gjDef.types.map { types[it.name] as SchemaWithData.Object }
                    } else {
                        gjLangTypeDef.memberTypes
                            .filter { (it as TypeName).name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                            .map { types[(it as TypeName).name] as SchemaWithData.Object }
                    }
                },
                isBase = gjLangTypeDef == gjDef.definition,
                appliedDirectives = if (gjLangTypeDef != null) {
                    decodeAppliedDirectivesFromLang(gjLangTypeDef.directives)
                } else {
                    decodeAppliedDirectives(gjDef)
                },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Directive ==========

    fun populate(directive: SchemaWithData.Directive) {
        val gjDef = directive.gjDef
        val isRepeatable = gjDef.isRepeatable
        val allowedLocations = gjDef.validLocations()
            .map { ViaductSchema.Directive.Location.valueOf(it.name) }
            .toSet()
        val sourceLocation = gjDef.definition?.sourceLocation?.sourceName?.let {
            ViaductSchema.SourceLocation(it)
        }
        val args = gjDef.arguments.map { argDef ->
            SchemaWithData.DirectiveArg(
                directive,
                argDef.name,
                decodeTypeExpr(argDef.type),
                emptyList(), // Directive args don't have applied directives
                decodeHasDefault(argDef),
                decodeDefaultValue(argDef),
                argDef, // data is GraphQLArgument
            )
        }
        directive.populate(isRepeatable, allowedLocations, sourceLocation, args)
    }

    // ========== Helper: OutputField ==========

    private fun createOutputField(
        fieldDef: GraphQLFieldDefinition,
        containingExtension: ViaductSchema.Extension<SchemaWithData.Record, SchemaWithData.Field>,
        appliedDirectives: List<ViaductSchema.AppliedDirective<*>>
    ): SchemaWithData.Field {
        return SchemaWithData.Field(
            containingExtension,
            fieldDef.name,
            decodeTypeExpr(fieldDef.type),
            appliedDirectives,
            hasDefault = false,
            mDefaultValue = null,
            data = fieldDef, // data is GraphQLFieldDefinition
            argsFactory = { field -> createFieldArgs(field, fieldDef) }
        )
    }

    private fun createFieldArgs(
        field: SchemaWithData.Field,
        fieldDef: GraphQLFieldDefinition
    ): List<SchemaWithData.FieldArg> =
        fieldDef.arguments.map { argDef ->
            SchemaWithData.FieldArg(
                field,
                argDef.name,
                decodeTypeExpr(argDef.type),
                decodeAppliedDirectives(argDef),
                decodeHasDefault(argDef),
                decodeDefaultValue(argDef),
                argDef, // data is GraphQLArgument
            )
        }
}
