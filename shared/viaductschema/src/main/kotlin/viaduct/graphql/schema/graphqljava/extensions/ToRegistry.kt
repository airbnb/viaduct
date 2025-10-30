@file:Suppress("MatchingDeclarationName")

package viaduct.graphql.schema.graphqljava.extensions

import graphql.language.Argument
import graphql.language.Directive
import graphql.language.DirectiveDefinition
import graphql.language.DirectiveLocation
import graphql.language.EnumTypeDefinition
import graphql.language.EnumTypeExtensionDefinition
import graphql.language.EnumValueDefinition
import graphql.language.FieldDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InputObjectTypeExtensionDefinition
import graphql.language.InputValueDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.InterfaceTypeExtensionDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.Type
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import graphql.language.UnionTypeExtensionDefinition
import graphql.language.Value
import graphql.schema.idl.TypeDefinitionRegistry
import viaduct.graphql.schema.ViaductSchema

data class TypeDefinitionRegistryOptions(
    val addStubsOnEmptyTypes: Boolean
) {
    companion object {
        val DEFAULT = TypeDefinitionRegistryOptions(addStubsOnEmptyTypes = true)
        val NO_STUBS = TypeDefinitionRegistryOptions(addStubsOnEmptyTypes = false)
    }
}

/** See KDoc for [ViaductSchema] for a background. */
fun ViaductSchema.toRegistry(options: TypeDefinitionRegistryOptions = TypeDefinitionRegistryOptions.DEFAULT): TypeDefinitionRegistry {
    val result = TypeDefinitionRegistry()
    this.types.entries.map { (_, value) ->
        when (value) {
            is ViaductSchema.Object -> {
                result.add(value.toObjectTypeDefinition(options))
                result.addAll(value.toObjectTypeDefinitionExtensions())
            }

            is ViaductSchema.Interface -> {
                result.add(value.toInterfaceTypeDefinition(options))
                result.addAll(value.toInterfaceTypeDefinitionExtensions())
            }

            is ViaductSchema.Union -> {
                result.add(value.unionTypeDefinition(options))
                result.addAll(value.unionTypeDefinitionExtensions())
            }

            is ViaductSchema.Enum -> {
                result.add(value.enumTypeDefinition())
                result.addAll(value.enumTypeDefinitionExtensions())
            }

            is ViaductSchema.Input -> {
                result.add(value.toInputObjectTypeDefinition())
                result.addAll(value.toInputObjectTypeDefinitionExtensions())
            }

            is ViaductSchema.Scalar -> result.add(value.scalarTypeDefinition())
            else -> throw IllegalArgumentException("Unexpected type definition: $value")
        }
    }

    // add the directive definitions to the registry
    if (options.addStubsOnEmptyTypes) {
        result.add(getViaductIgnoreSymbolAsObjectTypeDefinition())
    }
    this.directives.forEach { result.add(it.value.toDirectiveDefinition()) }
    return result
}

// Returns a registry with all the types merged into a single base type definition (without extension definitions)
fun ViaductSchema.toRegistryWithoutExtensionTypeDefinitions(options: TypeDefinitionRegistryOptions = TypeDefinitionRegistryOptions.DEFAULT): TypeDefinitionRegistry {
    val result = TypeDefinitionRegistry()
    this.types.entries.map { (_, value) ->
        when (value) {
            is ViaductSchema.Object -> {
                result.add(value.toMergedObjectTypeDefinition(options))
            }

            is ViaductSchema.Interface -> {
                result.add(value.toMergedInterfaceTypeDefinition(options))
            }

            is ViaductSchema.Union -> {
                result.add(value.toMergedUnionTypeDefinition(options))
            }

            is ViaductSchema.Enum -> {
                result.add(value.toMergedEnumTypeDefinition())
            }

            is ViaductSchema.Input -> {
                result.add(value.toMergedInputTypeDefinition())
            }

            is ViaductSchema.Scalar -> result.add(value.scalarTypeDefinition())
            else -> throw IllegalArgumentException("Unexpected type definition: $value")
        }
    }
    // add the directive definitions to the registry
    if (options.addStubsOnEmptyTypes) {
        result.add(getViaductIgnoreSymbolAsObjectTypeDefinition())
    }
    this.directives.forEach { result.add(it.value.toDirectiveDefinition()) }
    return result
}

fun ViaductSchema.Directive.toDirectiveDefinition() =
    DirectiveDefinition.newDirectiveDefinition()
        .name(name)
        .inputValueDefinitions(args.map { it.inputValueDefinition() })
        .repeatable(isRepeatable)
        .directiveLocations(allowedLocations.map { DirectiveLocation(it.name) })
        .build()

fun ViaductSchema.Scalar.scalarTypeDefinition() = ScalarTypeDefinition.newScalarTypeDefinition().name(this.name).build()

fun ViaductSchema.Object.toMergedObjectTypeDefinition(options: TypeDefinitionRegistryOptions = TypeDefinitionRegistryOptions.DEFAULT): ObjectTypeDefinition {
    val allFields = extensions.flatMap { it.members }
    // Add the viaduct ignore symbol to the object type
    // for empty record types cases so that graphql-java considers the
    // generated type registry as valid since graphql-java does not allow empty record types
    val allFieldDefs = allFields.map { it.toFieldDefinition() } +
        if (options.addStubsOnEmptyTypes) {
            getViaductIgnoreSymbolAsFieldDefinitionList()
        } else {
            emptyList()
        }

    return ObjectTypeDefinition.newObjectTypeDefinition()
        .name(extensions.first().def.name)
        .fieldDefinitions(allFieldDefs)
        .directives(extensions.map { it.appliedDirectives }.flatten().map { it.toDirectiveForTypeDefinition() })
        .implementz(extensions.flatMap { it.supers }.map { TypeName(it.name) })
        .build()
}

// This function is used to add viaduct ignore fields
// to empty record types in the schema so that graphql-java considers the
// generated type registry as valid since graphql-java does not allow empty record types
private fun getViaductIgnoreSymbolAsFieldDefinitionList(): List<FieldDefinition> {
    return listOf(
        FieldDefinition.newFieldDefinition()
            .name(ViaductSchema.VIADUCT_IGNORE_SYMBOL)
            .type(TypeName("String"))
            .build()
    )
}

// This function is used to add viaduct ignore an empty object type
// used to represent members of empty unions in the schema so that graphql-java considers the
// generated type registry as valid since graphql-java does not allow empty unions
private fun getViaductIgnoreSymbolAsObjectTypeDefinition(): ObjectTypeDefinition {
    return ObjectTypeDefinition.newObjectTypeDefinition()
        .name(ViaductSchema.VIADUCT_IGNORE_SYMBOL)
        .fieldDefinitions(getViaductIgnoreSymbolAsFieldDefinitionList())
        .build()
}

private fun ViaductSchema.SourceLocation.toSourceLocationDefinition() = graphql.language.SourceLocation(-1, -1, this.sourceName)

fun ViaductSchema.Object.toObjectTypeDefinition(options: TypeDefinitionRegistryOptions = TypeDefinitionRegistryOptions.DEFAULT) =
    ObjectTypeDefinition.newObjectTypeDefinition()
        .name(extensions.first().def.name)
        .fieldDefinitions(
            extensions.first().members.map { it.toFieldDefinition() } +
                if (options.addStubsOnEmptyTypes) getViaductIgnoreSymbolAsFieldDefinitionList() else emptyList()
        )
        .directives(extensions.first().appliedDirectives.map { it.toDirectiveForTypeDefinition() })
        .sourceLocation(sourceLocation?.toSourceLocationDefinition())
        .implementz(extensions.first().supers.map { TypeName(it.name) })
        .build()

fun ViaductSchema.Object.toObjectTypeDefinitionExtensions() =
    extensions.drop(1).mapNotNull { extension ->
        if (extension.members.isEmpty()) {
            return@mapNotNull null
        }
        ObjectTypeExtensionDefinition.newObjectTypeExtensionDefinition().name(extension.def.name)
            .fieldDefinitions(extension.members.map { it.toFieldDefinition() })
            .directives(extension.appliedDirectives.map { it.toDirectiveForTypeDefinition() })
            .sourceLocation(extension.sourceLocation?.toSourceLocationDefinition())
            .implementz(extension.supers.map { TypeName(it.name) })
            .build()
    }

fun ViaductSchema.Field.toFieldDefinition(): FieldDefinition {
    return FieldDefinition.newFieldDefinition().name(name)
        .type(type.toTypeForTypeDefinition())
        .inputValueDefinitions(args.map { it.inputValueDefinition() })
        .directives(appliedDirectives.map { it.toDirectiveForTypeDefinition() })
        .build()
}

fun ViaductSchema.Input.toMergedInputTypeDefinition(): InputObjectTypeDefinition {
    val allFields = extensions.flatMap { it.members }
    return InputObjectTypeDefinition.newInputObjectDefinition()
        .name(allFields.first().containingDef.name)
        .inputValueDefinitions(
            allFields.map { field ->
                InputValueDefinition.newInputValueDefinition()
                    .name(field.name)
                    .type(field.type.toTypeForTypeDefinition())
                    .directives(field.appliedDirectives.map { it.toDirectiveForTypeDefinition() })
                    .build()
            }
        )
        .directives(extensions.map { it.appliedDirectives }.flatten().map { it.toDirectiveForTypeDefinition() })
        .sourceLocation(sourceLocation?.toSourceLocationDefinition())
        .build()
}

fun ViaductSchema.Input.toInputObjectTypeDefinition() =
    InputObjectTypeDefinition.newInputObjectDefinition()
        .name(extensions.first().def.name)
        .inputValueDefinitions(
            extensions.first().members.map { field ->
                InputValueDefinition.newInputValueDefinition()
                    .name(field.name)
                    .type(field.type.toTypeForTypeDefinition())
                    .directives(field.appliedDirectives.map { it.toDirectiveForTypeDefinition() })
                    .build()
            }
        )
        .directives(extensions.first().appliedDirectives.map { it.toDirectiveForTypeDefinition() })
        .sourceLocation(sourceLocation?.toSourceLocationDefinition())
        .build()

fun ViaductSchema.Input.toInputObjectTypeDefinitionExtensions() =
    extensions.drop(1).map { extension ->
        InputObjectTypeExtensionDefinition.newInputObjectTypeExtensionDefinition()
            .name(extension.def.name)
            .inputValueDefinitions(
                extension.members.map {
                    InputValueDefinition.newInputValueDefinition()
                        .name(it.name)
                        .type(it.type.toTypeForTypeDefinition())
                        .directives(it.appliedDirectives.map { it.toDirectiveForTypeDefinition() })
                        .build()
                }
            )
            .directives(extension.appliedDirectives.map { it.toDirectiveForTypeDefinition() })
            .sourceLocation(sourceLocation?.toSourceLocationDefinition())
            .build()
    }

fun ViaductSchema.Interface.toMergedInterfaceTypeDefinition(options: TypeDefinitionRegistryOptions = TypeDefinitionRegistryOptions.DEFAULT): InterfaceTypeDefinition {
    val allFields = extensions.flatMap { it.members }
    // Add the viaduct ignore symbol to the interface type
    // for empty interface cases so that graphql-java considers the
    // generated type registry as valid since graphql-java does not allow empty interfaces
    val allFieldDefs = allFields.map { it.toFieldDefinition() } +
        if (options.addStubsOnEmptyTypes) {
            getViaductIgnoreSymbolAsFieldDefinitionList()
        } else {
            emptyList()
        }
    return InterfaceTypeDefinition.newInterfaceTypeDefinition()
        .name(extensions.first().def.name)
        .definitions(allFieldDefs)
        .directives(extensions.map { it.appliedDirectives }.flatten().map { it.toDirectiveForTypeDefinition() })
        .sourceLocation(sourceLocation?.toSourceLocationDefinition())
        .implementz(extensions.flatMap { it.supers.map { TypeName(it.name) } })
        .build()
}

fun ViaductSchema.Interface.toInterfaceTypeDefinition(options: TypeDefinitionRegistryOptions = TypeDefinitionRegistryOptions.DEFAULT) =
    InterfaceTypeDefinition.newInterfaceTypeDefinition()
        .name(extensions.first().def.name)
        .definitions(
            extensions.first().members.map { it.toFieldDefinition() } +
                if (options.addStubsOnEmptyTypes) getViaductIgnoreSymbolAsFieldDefinitionList() else emptyList()
        )
        .directives(extensions.first().appliedDirectives.map { it.toDirectiveForTypeDefinition() })
        .sourceLocation(sourceLocation?.toSourceLocationDefinition())
        .implementz(extensions.first().supers.map { TypeName(it.name) })
        .build()

fun ViaductSchema.Interface.toInterfaceTypeDefinitionExtensions() =
    extensions.drop(1).mapNotNull { extension ->
        if (extension.members.isEmpty()) {
            return@mapNotNull null
        }
        InterfaceTypeExtensionDefinition.newInterfaceTypeExtensionDefinition().name(extension.def.name)
            .definitions(extension.members.map { it.toFieldDefinition() })
            .sourceLocation(sourceLocation?.toSourceLocationDefinition())
            .directives(extension.appliedDirectives.map { it.toDirectiveForTypeDefinition() })
            .implementz(extension.supers.map { TypeName(it.name) })
            .build()
    }

fun ViaductSchema.Arg.inputValueDefinition() =
    InputValueDefinition.newInputValueDefinition().name(name)
        .type(type.toTypeForTypeDefinition())
        .defaultValue(if (hasDefault) effectiveDefaultValue as Value<*>? else null)
        .directives(appliedDirectives.map { it.toDirectiveForTypeDefinition() })
        .build()

fun ViaductSchema.AppliedDirective.toDirectiveForTypeDefinition() =
    Directive.newDirective().name(name)
        .arguments(
            arguments.entries.map { arg ->
                Argument.newArgument()
                    .name(arg.key)
                    .value(arg.value as Value<*>?)
                    .build()
            }.filter { it.value != null }
        ).build()

fun ViaductSchema.Union.toMergedUnionTypeDefinition(options: TypeDefinitionRegistryOptions = TypeDefinitionRegistryOptions.DEFAULT): UnionTypeDefinition {
    val allMembers = extensions.flatMap { it.members }

    // Add the viaduct ignore symbol to the union type
    // for empty unions cases so that graphql-java considers the
    // generated type registry as valid since graphql-java does not allow empty unions
    val allMemberDefs = allMembers.map { TypeName(it.name) } +
        if (options.addStubsOnEmptyTypes) {
            listOf(TypeName(ViaductSchema.VIADUCT_IGNORE_SYMBOL))
        } else {
            emptyList()
        }
    return UnionTypeDefinition.newUnionTypeDefinition()
        .name(extensions.first().def.name)
        .memberTypes(allMemberDefs)
        .sourceLocation(sourceLocation?.toSourceLocationDefinition())
        .directives(extensions.map { it.appliedDirectives }.flatten().map { it.toDirectiveForTypeDefinition() })
        .build()
}

fun ViaductSchema.Union.unionTypeDefinition(options: TypeDefinitionRegistryOptions = TypeDefinitionRegistryOptions.DEFAULT) =
    UnionTypeDefinition.newUnionTypeDefinition()
        .name(extensions.first().def.name)
        .memberTypes(
            extensions.first().members.map { TypeName(it.name) } +
                if (options.addStubsOnEmptyTypes) listOf(TypeName(ViaductSchema.VIADUCT_IGNORE_SYMBOL)) else emptyList()
        )
        .directives(appliedDirectives.map { it.toDirectiveForTypeDefinition() })
        .sourceLocation(sourceLocation?.toSourceLocationDefinition())
        .build()

fun ViaductSchema.Union.unionTypeDefinitionExtensions() =
    extensions.drop(1).mapNotNull { extension ->
        if (extension.members.isEmpty()) {
            return@mapNotNull null
        }
        val directives = extension.appliedDirectives.map { it.toDirectiveForTypeDefinition() }
        val memberTypes = extension.members.map { TypeName(it.name) }
        UnionTypeExtensionDefinition.newUnionTypeExtensionDefinition().name(name)
            .memberTypes(memberTypes)
            .sourceLocation(sourceLocation?.toSourceLocationDefinition())
            .directives(directives)
            .build()
    }

fun ViaductSchema.Enum.toMergedEnumTypeDefinition(): EnumTypeDefinition {
    val allMembers = extensions.flatMap { it.members }
    return EnumTypeDefinition.newEnumTypeDefinition()
        .name(extensions.first().def.name)
        .enumValueDefinitions(
            allMembers.map {
                EnumValueDefinition.newEnumValueDefinition()
                    .name(it.name)
                    .directives(it.appliedDirectives.map { it.toDirectiveForTypeDefinition() })
                    .build()
            }
        )
        .directives(extensions.map { it.appliedDirectives }.flatten().map { it.toDirectiveForTypeDefinition() })
        .sourceLocation(sourceLocation?.toSourceLocationDefinition())
        .build()
}

fun ViaductSchema.Enum.enumTypeDefinition() =
    EnumTypeDefinition.newEnumTypeDefinition()
        .name(extensions.first().def.name)
        .enumValueDefinitions(
            extensions.first().members.map {
                EnumValueDefinition.newEnumValueDefinition()
                    .name(it.name)
                    .directives(it.appliedDirectives.map { it.toDirectiveForTypeDefinition() })
                    .build()
            }
        )
        .directives(appliedDirectives.map { it.toDirectiveForTypeDefinition() })
        .sourceLocation(sourceLocation?.toSourceLocationDefinition())
        .build()

fun ViaductSchema.Enum.enumTypeDefinitionExtensions() =
    extensions.drop(1).map { extension ->
        EnumTypeExtensionDefinition.newEnumTypeExtensionDefinition()
            .name(this.name)
            .enumValueDefinitions(
                extension.members.map {
                    EnumValueDefinition.newEnumValueDefinition()
                        .name(it.name)
                        .directives(it.appliedDirectives.map { it.toDirectiveForTypeDefinition() })
                        .build()
                }
            )
            .directives(extension.appliedDirectives.map { it.toDirectiveForTypeDefinition() })
            .sourceLocation(sourceLocation?.toSourceLocationDefinition())
            .build()
    }

private fun ViaductSchema.TypeExpr.constructNestedListType(
    baseType: Type<*>,
    listDepth: Int
): Type<*> =
    (listDepth - 1 downTo 0).fold(baseType) { currentType, index ->
        val listType = ListType(currentType)
        if (nullableAtDepth(index)) listType else NonNullType(listType)
    }

fun ViaductSchema.TypeExpr.toTypeForTypeDefinition(): Type<*> {
    val typeName = TypeName(baseTypeDef.name)
    val baseType = if (!baseTypeNullable) NonNullType(typeName) else typeName
    return if (isList) {
        this.constructNestedListType(baseType, listDepth)
    } else {
        baseType
    }
}
