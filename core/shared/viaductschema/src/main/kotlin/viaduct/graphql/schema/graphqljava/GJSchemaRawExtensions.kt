package viaduct.graphql.schema.graphqljava

import graphql.language.DirectiveDefinition
import graphql.language.EnumTypeDefinition
import graphql.language.EnumTypeExtensionDefinition
import graphql.language.EnumValueDefinition
import graphql.language.FieldDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InputObjectTypeExtensionDefinition
import graphql.language.InputValueDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.InterfaceTypeExtensionDefinition
import graphql.language.NamedNode
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.ScalarTypeExtensionDefinition
import graphql.language.TypeDefinition
import graphql.language.UnionTypeDefinition
import graphql.language.UnionTypeExtensionDefinition
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema

/**
 * Extension properties for accessing graphql-java language types from a GJSchemaRaw
 * created via [ViaductSchema.fromTypeDefinitionRegistry] or [ViaductSchema.fromSDL].
 */

// ========== Def (base) ==========

/** The graphql-java language node for this definition. */
internal val SchemaWithData.Def.gjrDef: NamedNode<*>
    get() = when (val d = data) {
        is TypeDefData<*, *> -> d.def
        is NamedNode<*> -> d
        else -> error("Unexpected data type: ${d?.javaClass}")
    }

// ========== Directive ==========

/** The graphql-java DirectiveDefinition for this directive. */
internal val SchemaWithData.Directive.gjrDef: DirectiveDefinition
    get() = data as DirectiveDefinition

// ========== TypeDef (base) ==========

/** The graphql-java TypeDefinition for this type. */
internal val SchemaWithData.TypeDef.gjrDef: TypeDefinition<*>
    get() = (data as TypeDefData<*, *>).def

/** The graphql-java extension definitions for this type. */
internal val SchemaWithData.TypeDef.gjrExtensionDefs: List<TypeDefinition<*>>
    get() = (data as TypeDefData<*, *>).extensionDefs

// ========== Scalar ==========

/** The graphql-java ScalarTypeDefinition for this scalar. */
internal val SchemaWithData.Scalar.gjrDef: ScalarTypeDefinition
    get() = (data as TypeDefData<*, *>).def as ScalarTypeDefinition

/** The graphql-java extension definitions for this scalar. */
internal val SchemaWithData.Scalar.gjrExtensionDefs: List<ScalarTypeExtensionDefinition>
    @Suppress("UNCHECKED_CAST")
    get() = (data as TypeDefData<*, *>).extensionDefs as List<ScalarTypeExtensionDefinition>

// ========== Enum ==========

/** The graphql-java EnumTypeDefinition for this enum. */
internal val SchemaWithData.Enum.gjrDef: EnumTypeDefinition
    get() = (data as TypeDefData<*, *>).def as EnumTypeDefinition

/** The graphql-java extension definitions for this enum. */
internal val SchemaWithData.Enum.gjrExtensionDefs: List<EnumTypeExtensionDefinition>
    @Suppress("UNCHECKED_CAST")
    get() = (data as TypeDefData<*, *>).extensionDefs as List<EnumTypeExtensionDefinition>

// ========== Union ==========

/** The graphql-java UnionTypeDefinition for this union. */
internal val SchemaWithData.Union.gjrDef: UnionTypeDefinition
    get() = (data as TypeDefData<*, *>).def as UnionTypeDefinition

/** The graphql-java extension definitions for this union. */
internal val SchemaWithData.Union.gjrExtensionDefs: List<UnionTypeExtensionDefinition>
    @Suppress("UNCHECKED_CAST")
    get() = (data as TypeDefData<*, *>).extensionDefs as List<UnionTypeExtensionDefinition>

// ========== Interface ==========

/** The graphql-java InterfaceTypeDefinition for this interface. */
internal val SchemaWithData.Interface.gjrDef: InterfaceTypeDefinition
    get() = (data as TypeDefData<*, *>).def as InterfaceTypeDefinition

/** The graphql-java extension definitions for this interface. */
internal val SchemaWithData.Interface.gjrExtensionDefs: List<InterfaceTypeExtensionDefinition>
    @Suppress("UNCHECKED_CAST")
    get() = (data as TypeDefData<*, *>).extensionDefs as List<InterfaceTypeExtensionDefinition>

// ========== Object ==========

/** The graphql-java ObjectTypeDefinition for this object. */
internal val SchemaWithData.Object.gjrDef: ObjectTypeDefinition
    get() = (data as TypeDefData<*, *>).def as ObjectTypeDefinition

/** The graphql-java extension definitions for this object. */
internal val SchemaWithData.Object.gjrExtensionDefs: List<ObjectTypeExtensionDefinition>
    @Suppress("UNCHECKED_CAST")
    get() = (data as TypeDefData<*, *>).extensionDefs as List<ObjectTypeExtensionDefinition>

// ========== Input ==========

/** The graphql-java InputObjectTypeDefinition for this input. */
internal val SchemaWithData.Input.gjrDef: InputObjectTypeDefinition
    get() = (data as TypeDefData<*, *>).def as InputObjectTypeDefinition

/** The graphql-java extension definitions for this input. */
internal val SchemaWithData.Input.gjrExtensionDefs: List<InputObjectTypeExtensionDefinition>
    @Suppress("UNCHECKED_CAST")
    get() = (data as TypeDefData<*, *>).extensionDefs as List<InputObjectTypeExtensionDefinition>

// ========== EnumValue ==========

/** The graphql-java EnumValueDefinition for this enum value. */
internal val SchemaWithData.EnumValue.gjrDef: EnumValueDefinition
    get() = data as EnumValueDefinition

// ========== Field ==========

/**
 * The graphql-java definition for this field.
 * Returns [FieldDefinition] for output fields or [InputValueDefinition] for input fields.
 */
internal val SchemaWithData.Field.gjrDef: NamedNode<*>
    get() = data as NamedNode<*>

/** The graphql-java FieldDefinition for this output field. */
internal val SchemaWithData.Field.gjrOutputDef: FieldDefinition
    get() = data as FieldDefinition

/** The graphql-java InputValueDefinition for this input field. */
internal val SchemaWithData.Field.gjrInputDef: InputValueDefinition
    get() = data as InputValueDefinition

// ========== FieldArg ==========

/** The graphql-java InputValueDefinition for this field argument. */
internal val SchemaWithData.FieldArg.gjrDef: InputValueDefinition
    get() = data as InputValueDefinition

// ========== DirectiveArg ==========

/** The graphql-java InputValueDefinition for this directive argument. */
internal val SchemaWithData.DirectiveArg.gjrDef: InputValueDefinition
    get() = data as InputValueDefinition
