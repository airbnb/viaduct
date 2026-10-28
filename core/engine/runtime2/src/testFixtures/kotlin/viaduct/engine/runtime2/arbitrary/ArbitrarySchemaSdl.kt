package viaduct.engine.runtime2.arbitrary

import graphql.language.EnumTypeDefinition
import graphql.language.FieldDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.Type
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import graphql.parser.Parser

/** Rebuilds a generated schema model from the SDL it printed, plus the generator metadata SDL cannot carry. */
internal fun arbitrarySchemaFromSdl(
    sdl: String,
    deepFields: Map<String, String>,
    rootFieldReferenceFamily: RootFieldReferenceFamily?,
    features: SchemaFeatures,
): ArbitrarySchema {
    val definitions = Parser.parse(sdl).definitions
    val objectDefinitions = definitions.filterIsInstance<ObjectTypeDefinition>()
    val allObjects = objectDefinitions.map(::objectDefinition)
    val membersByInterface =
        objectDefinitions
            .flatMap { definition -> definition.implements.map { type -> (type as TypeName).name to definition.name } }
            .groupBy({ (interfaceName, _) -> interfaceName }, { (_, member) -> member })
    return ArbitrarySchema(
        sdl = sdl,
        objects = allObjects.filterNot { objectType -> objectType.name == "Query" || objectType.name == GENERATED_HASH_TYPE },
        query = allObjects.single { objectType -> objectType.name == "Query" },
        hashType = allObjects.single { objectType -> objectType.name == GENERATED_HASH_TYPE },
        interfaces =
            definitions.filterIsInstance<InterfaceTypeDefinition>().map { definition ->
                InterfaceDefinitionSpec(
                    name = definition.name,
                    members = membersByInterface[definition.name].orEmpty().toCollection(linkedSetOf()),
                    fields = definition.fieldDefinitions.map { field -> fieldDefinition(definition.name, field) },
                )
            },
        unions =
            definitions.filterIsInstance<UnionTypeDefinition>().map { definition ->
                UnionDefinitionSpec(definition.name, definition.memberTypes.mapTo(linkedSetOf()) { member -> (member as TypeName).name })
            },
        enums =
            definitions.filterIsInstance<EnumTypeDefinition>().map { definition ->
                EnumDefinitionSpec(definition.name, definition.enumValueDefinitions.map { value -> value.name })
            },
        inputObjects =
            definitions.filterIsInstance<InputObjectTypeDefinition>().map { definition ->
                InputObjectDefinitionSpec(
                    name = definition.name,
                    fields = definition.inputValueDefinitions.map { field -> InputFieldDefinitionSpec(field.name, inputTypeSpec(field.type)) },
                )
            },
        deepFields = deepFields,
        rootFieldReferenceFamily = rootFieldReferenceFamily,
        features = features,
    )
}

private fun objectDefinition(definition: ObjectTypeDefinition): ObjectDefinition {
    val interfaceNames = definition.implements.map { type -> (type as TypeName).name }
    val implementsNode = interfaceNames.firstOrNull() == "Node"
    // The generator prints Node's `id` field itself rather than listing it among the object's fields.
    val fields =
        if (implementsNode) {
            require(definition.fieldDefinitions.firstOrNull()?.name == "id") { "Node implementation ${definition.name} must start with its id field" }
            definition.fieldDefinitions.drop(1)
        } else {
            definition.fieldDefinitions
        }
    return ObjectDefinition(
        name = definition.name,
        implementsNode = implementsNode,
        interfaces = interfaceNames.drop(if (implementsNode) 1 else 0).toCollection(linkedSetOf()),
        fields = fields.map { field -> fieldDefinition(definition.name, field) },
    )
}

private fun fieldDefinition(
    ownerName: String,
    field: FieldDefinition,
): FieldDefinitionSpec =
    FieldDefinitionSpec(
        ownerName = ownerName,
        name = field.name,
        type = outputTypeSpec(field.type),
        arguments = field.inputValueDefinitions.map { argument -> ArgumentDefinitionSpec(argument.name, inputTypeSpec(argument.type), argument.defaultValue) },
        isParentField = field.hasDirective("parent"),
    )

private fun outputTypeSpec(type: Type<*>): OutputTypeSpec {
    val nullabilities = mutableListOf<Boolean>()
    var current = type
    while (true) {
        nullabilities += current !is NonNullType
        if (current is NonNullType) current = current.type
        if (current !is ListType) break
        current = current.type
    }
    val list = nullabilities.size > 1
    return OutputTypeSpec(
        namedType = (current as TypeName).name,
        nullable = nullabilities.first(),
        list = list,
        elementNullable = list && nullabilities[1],
        nestedElementNullabilities = nullabilities.drop(2),
    )
}

private fun inputTypeSpec(type: Type<*>): InputTypeSpec {
    val nullable = type !is NonNullType
    return when (val unwrapped = if (type is NonNullType) type.type else type) {
        is ListType -> ListInputTypeSpec(inputTypeSpec(unwrapped.type), nullable)
        is TypeName ->
            ScalarKind.entries.singleOrNull { scalar -> scalar.graphQLName == unwrapped.name }?.let { scalar -> ScalarInputTypeSpec(scalar, nullable) }
                ?: InputObjectInputTypeSpec(unwrapped.name, nullable)
        else -> error("Unsupported input type $unwrapped")
    }
}
