package viaduct.graphql.schema.graphqljava

import graphql.language.Directive
import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.Node
import graphql.language.Type
import graphql.language.TypeName
import graphql.schema.idl.TypeDefinitionRegistry
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema

/**
 * Transforms graphql-java TypeDefinitionRegistry elements into SchemaWithData elements.
 * This class centralizes all AST-to-ViaductSchema transformation logic, separating it
 * from the populate() methods in TypeDef classes.
 */
internal class TypeDefinitionRegistryDecoder(
    private val registry: TypeDefinitionRegistry,
    private val types: Map<String, SchemaWithData.TypeDef>,
    private val directives: Map<String, SchemaWithData.Directive>,
) {
    // ========== Core Decoding Primitives ==========

    fun decodeTypeExpr(type: Type<*>): ViaductSchema.TypeExpr<SchemaWithData.TypeDef> =
        type.toTypeExpr { baseTypeDefName, baseTypeNullable, listNullable ->
            ViaductSchema.TypeExpr(
                types[baseTypeDefName] ?: error("Type not found: $baseTypeDefName"),
                baseTypeNullable,
                listNullable
            )
        }

    fun decodeSourceLocation(node: Node<*>?): ViaductSchema.SourceLocation? = node?.sourceLocation?.sourceName?.let { ViaductSchema.SourceLocation(it) }

    fun decodeAppliedDirectives(langDirectives: List<Directive>): List<ViaductSchema.AppliedDirective<*>> = langDirectives.map { decodeAppliedDirective(it) }

    private fun decodeAppliedDirective(dir: Directive): ViaductSchema.AppliedDirective<*> {
        val def = registry.getDirectiveDefinition(dir.name).orElse(null)
            ?: error("Directive @${dir.name} not found in schema.")
        val directiveDef = directives[dir.name]
            ?: error("Directive @${dir.name} not found in directives map.")
        return dir.toAppliedDirective(def, directiveDef) { decodeTypeExpr(it) }
    }

    fun decodeHasDefault(ivd: InputValueDefinition): Boolean = ivd.defaultValue != null

    fun decodeDefaultValue(ivd: InputValueDefinition): ViaductSchema.Literal? =
        if (ivd.defaultValue != null) {
            ValueConverter.convert(decodeTypeExpr(ivd.type), ivd.defaultValue)
        } else {
            null
        }

    // ========== Scalar ==========

    fun createScalarExtensions(scalarDef: SchemaWithData.Scalar): List<ViaductSchema.Extension<SchemaWithData.Scalar, Nothing>> {
        return (listOf(scalarDef.gjrDef) + scalarDef.gjrExtensionDefs).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = scalarDef,
                memberFactory = { _ -> emptyList() },
                isBase = gjLangTypeDef == scalarDef.gjrDef,
                appliedDirectives = decodeAppliedDirectives(gjLangTypeDef.directives),
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Enum ==========

    fun createEnumExtensions(enumDef: SchemaWithData.Enum): List<ViaductSchema.Extension<SchemaWithData.Enum, SchemaWithData.EnumValue>> {
        return (listOf(enumDef.gjrDef) + enumDef.gjrExtensionDefs).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = enumDef,
                memberFactory = { containingExtension ->
                    gjLangTypeDef.enumValueDefinitions.map { evd ->
                        SchemaWithData.EnumValue(
                            containingExtension,
                            evd.name,
                            decodeAppliedDirectives(evd.directives),
                            evd
                        )
                    }
                },
                isBase = gjLangTypeDef == enumDef.gjrDef,
                appliedDirectives = decodeAppliedDirectives(gjLangTypeDef.directives),
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Input ==========

    fun createInputExtensions(inputDef: SchemaWithData.Input): List<ViaductSchema.Extension<SchemaWithData.Input, SchemaWithData.Field>> {
        return (listOf(inputDef.gjrDef) + inputDef.gjrExtensionDefs).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = inputDef,
                memberFactory = { containingExtension ->
                    gjLangTypeDef.inputValueDefinitions.map { ivd ->
                        SchemaWithData.Field(
                            containingExtension,
                            ivd.name,
                            decodeTypeExpr(ivd.type),
                            decodeAppliedDirectives(ivd.directives),
                            decodeHasDefault(ivd),
                            decodeDefaultValue(ivd),
                            ivd
                        )
                    }
                },
                isBase = gjLangTypeDef == inputDef.gjrDef,
                appliedDirectives = decodeAppliedDirectives(gjLangTypeDef.directives),
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Interface ==========

    fun createInterfaceExtensions(interfaceDef: SchemaWithData.Interface): List<ViaductSchema.ExtensionWithSupers<SchemaWithData.Interface, SchemaWithData.Field>> {
        return (listOf(interfaceDef.gjrDef) + interfaceDef.gjrExtensionDefs).map { gjLangTypeDef ->
            ViaductSchema.ExtensionWithSupers.of(
                def = interfaceDef,
                memberFactory = { containingExtension ->
                    gjLangTypeDef.fieldDefinitions
                        .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                        .map { fieldDef ->
                            @Suppress("UNCHECKED_CAST")
                            createOutputField(
                                fieldDef,
                                containingExtension as ViaductSchema.Extension<SchemaWithData.Record, SchemaWithData.Field>
                            )
                        }
                },
                isBase = gjLangTypeDef == interfaceDef.gjrDef,
                appliedDirectives = decodeAppliedDirectives(gjLangTypeDef.directives),
                supers = gjLangTypeDef.implements.map { types[(it as TypeName).name] as SchemaWithData.Interface },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Object ==========

    fun createObjectExtensions(objectDef: SchemaWithData.Object): List<ViaductSchema.ExtensionWithSupers<SchemaWithData.Object, SchemaWithData.Field>> {
        return (listOf(objectDef.gjrDef) + objectDef.gjrExtensionDefs).map { gjLangTypeDef ->
            ViaductSchema.ExtensionWithSupers.of(
                def = objectDef,
                memberFactory = { containingExtension ->
                    gjLangTypeDef.fieldDefinitions
                        .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                        .map { fieldDef ->
                            @Suppress("UNCHECKED_CAST")
                            createOutputField(
                                fieldDef,
                                containingExtension as ViaductSchema.Extension<SchemaWithData.Record, SchemaWithData.Field>
                            )
                        }
                },
                isBase = gjLangTypeDef == objectDef.gjrDef,
                appliedDirectives = decodeAppliedDirectives(gjLangTypeDef.directives),
                supers = gjLangTypeDef.implements.map { types[(it as TypeName).name] as SchemaWithData.Interface },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Union ==========

    fun createUnionExtensions(unionDef: SchemaWithData.Union): List<ViaductSchema.Extension<SchemaWithData.Union, SchemaWithData.Object>> {
        return (listOf(unionDef.gjrDef) + unionDef.gjrExtensionDefs).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = unionDef,
                memberFactory = { _ ->
                    gjLangTypeDef.memberTypes
                        .filter { (it as TypeName).name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                        .map { types[(it as TypeName).name] as SchemaWithData.Object }
                },
                isBase = gjLangTypeDef == unionDef.gjrDef,
                appliedDirectives = decodeAppliedDirectives(gjLangTypeDef.directives),
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Directive ==========

    fun populate(directive: SchemaWithData.Directive) {
        val def = directive.gjrDef
        val isRepeatable = def.isRepeatable
        val allowedLocations = def.directiveLocations
            .map { ViaductSchema.Directive.Location.valueOf(it.name) }
            .toSet()
        val sourceLocation = decodeSourceLocation(def)
        val args = def.inputValueDefinitions.map {
            val hasDefault = it.defaultValue != null
            val default = if (hasDefault) {
                ValueConverter.convert(decodeTypeExpr(it.type), it.defaultValue)
            } else {
                null
            }
            SchemaWithData.DirectiveArg(directive, it.name, decodeTypeExpr(it.type), emptyList(), hasDefault, default, it)
        }
        directive.populate(isRepeatable, allowedLocations, sourceLocation, args)
    }

    // ========== Helper: OutputField ==========

    private fun createOutputField(
        fieldDef: FieldDefinition,
        containingExtension: ViaductSchema.Extension<SchemaWithData.Record, SchemaWithData.Field>
    ): SchemaWithData.Field {
        return SchemaWithData.Field(
            containingExtension = containingExtension,
            name = fieldDef.name,
            type = decodeTypeExpr(fieldDef.type),
            appliedDirectives = decodeAppliedDirectives(fieldDef.directives),
            hasDefault = false,
            mDefaultValue = null,
            data = fieldDef,
            argsFactory = { field -> createFieldArgs(field, fieldDef) }
        )
    }

    private fun createFieldArgs(
        field: SchemaWithData.Field,
        fieldDef: FieldDefinition
    ): List<SchemaWithData.FieldArg> =
        fieldDef.inputValueDefinitions.map { ivd ->
            val hasDefault = ivd.defaultValue != null
            val default = if (hasDefault) {
                ValueConverter.convert(decodeTypeExpr(ivd.type), ivd.defaultValue)
            } else {
                null
            }
            SchemaWithData.FieldArg(
                field,
                ivd.name,
                decodeTypeExpr(ivd.type),
                decodeAppliedDirectives(ivd.directives),
                hasDefault,
                default,
                ivd
            )
        }
}
