package viaduct.graphql.schema.graphqljava

import graphql.language.DirectiveDefinition
import graphql.language.EnumTypeDefinition
import graphql.language.EnumTypeExtensionDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InputObjectTypeExtensionDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.InterfaceTypeExtensionDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.ScalarTypeExtensionDefinition
import graphql.language.UnionTypeDefinition
import graphql.language.UnionTypeExtensionDefinition
import viaduct.graphql.schema.SchemaWithData
import viaduct.utils.collections.HMap

/**
 * Internal access to graphql-java language types stored by GJSchemaRaw.
 */

private val gjSchemaRawKey = HMap.Key.of<Any?>("GJSchemaRaw")

internal fun gjSchemaRawHolder(value: Any?): HMap =
    HMap.Builder()
        .put(gjSchemaRawKey, value)
        .build()

/** The graphql-java DirectiveDefinition for this directive. */
internal val SchemaWithData.Directive.gjrDef: DirectiveDefinition
    get() = holder[gjSchemaRawKey] as DirectiveDefinition

/** The graphql-java ScalarTypeDefinition for this scalar. */
internal val SchemaWithData.Scalar.gjrDef: ScalarTypeDefinition
    get() = (holder[gjSchemaRawKey] as TypeDefData<*, *>).def as ScalarTypeDefinition

/** The graphql-java extension definitions for this scalar. */
internal val SchemaWithData.Scalar.gjrExtensionDefs: List<ScalarTypeExtensionDefinition>
    @Suppress("UNCHECKED_CAST")
    get() = (holder[gjSchemaRawKey] as TypeDefData<*, *>).extensionDefs as List<ScalarTypeExtensionDefinition>

/** The graphql-java EnumTypeDefinition for this enum. */
internal val SchemaWithData.Enum.gjrDef: EnumTypeDefinition
    get() = (holder[gjSchemaRawKey] as TypeDefData<*, *>).def as EnumTypeDefinition

/** The graphql-java extension definitions for this enum. */
internal val SchemaWithData.Enum.gjrExtensionDefs: List<EnumTypeExtensionDefinition>
    @Suppress("UNCHECKED_CAST")
    get() = (holder[gjSchemaRawKey] as TypeDefData<*, *>).extensionDefs as List<EnumTypeExtensionDefinition>

/** The graphql-java UnionTypeDefinition for this union. */
internal val SchemaWithData.Union.gjrDef: UnionTypeDefinition
    get() = (holder[gjSchemaRawKey] as TypeDefData<*, *>).def as UnionTypeDefinition

/** The graphql-java extension definitions for this union. */
internal val SchemaWithData.Union.gjrExtensionDefs: List<UnionTypeExtensionDefinition>
    @Suppress("UNCHECKED_CAST")
    get() = (holder[gjSchemaRawKey] as TypeDefData<*, *>).extensionDefs as List<UnionTypeExtensionDefinition>

/** The graphql-java InterfaceTypeDefinition for this interface. */
internal val SchemaWithData.Interface.gjrDef: InterfaceTypeDefinition
    get() = (holder[gjSchemaRawKey] as TypeDefData<*, *>).def as InterfaceTypeDefinition

/** The graphql-java extension definitions for this interface. */
internal val SchemaWithData.Interface.gjrExtensionDefs: List<InterfaceTypeExtensionDefinition>
    @Suppress("UNCHECKED_CAST")
    get() = (holder[gjSchemaRawKey] as TypeDefData<*, *>).extensionDefs as List<InterfaceTypeExtensionDefinition>

/** The graphql-java ObjectTypeDefinition for this object. */
internal val SchemaWithData.Object.gjrDef: ObjectTypeDefinition
    get() = (holder[gjSchemaRawKey] as TypeDefData<*, *>).def as ObjectTypeDefinition

/** The graphql-java extension definitions for this object. */
internal val SchemaWithData.Object.gjrExtensionDefs: List<ObjectTypeExtensionDefinition>
    @Suppress("UNCHECKED_CAST")
    get() = (holder[gjSchemaRawKey] as TypeDefData<*, *>).extensionDefs as List<ObjectTypeExtensionDefinition>

/** The graphql-java InputObjectTypeDefinition for this input. */
internal val SchemaWithData.Input.gjrDef: InputObjectTypeDefinition
    get() = (holder[gjSchemaRawKey] as TypeDefData<*, *>).def as InputObjectTypeDefinition

/** The graphql-java extension definitions for this input. */
internal val SchemaWithData.Input.gjrExtensionDefs: List<InputObjectTypeExtensionDefinition>
    @Suppress("UNCHECKED_CAST")
    get() = (holder[gjSchemaRawKey] as TypeDefData<*, *>).extensionDefs as List<InputObjectTypeExtensionDefinition>
