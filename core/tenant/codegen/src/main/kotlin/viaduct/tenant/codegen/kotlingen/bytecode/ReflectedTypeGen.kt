@file:Suppress("ClassNaming")

package viaduct.tenant.codegen.kotlingen.bytecode

import getEscapedFieldName
import viaduct.apiannotations.VisibleForTest
import viaduct.codegen.km.kotlinTypeString
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.codegen.utils.JavaName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.hasFieldsObject
import viaduct.tenant.codegen.bytecode.config.hasReflectedType
import viaduct.tenant.codegen.bytecode.config.isRootObjectFieldEligible
import viaduct.tenant.codegen.bytecode.config.kmType
import viaduct.tenant.codegen.bytecode.config.pathFromQueryRoot
import viaduct.tenant.codegen.bytecode.config.reflectedFields

@VisibleForTest
fun KotlinGRTFilesBuilder.reflectedTypeGen(def: ViaductSchema.TypeDef): STContents =
    STContents(stGroup, ReflectedTypeModelImpl(pkg, def.name, def.reflectedFields, def.hasFieldsObject, baseTypeMapper))

@VisibleForTest
fun KotlinGRTFilesBuilder.fieldsObjectGen(def: ViaductSchema.TypeDef): STContents {
    val pathToParentObject = def.pathFromQueryRoot(reverseSchema, schema.queryTypeDef)
    return STContents(fieldsSTGroup, ReflectedTypeModelImpl(pkg, def.name, def.reflectedFields, def.hasFieldsObject, baseTypeMapper, pathToParentObject))
}

/**
 * Reflection generation for Arguments GRTs, which have no backing [ViaductSchema.TypeDef] — only a
 * name and a field-argument list. Arguments always get a Fields object, and no argument can be a
 * root-object field, so [pathToParentObject] is always null.
 */
@VisibleForTest
fun KotlinGRTFilesBuilder.reflectedTypeGenForArguments(
    className: String,
    fields: Iterable<ViaductSchema.HasDefaultValue>
): STContents = STContents(stGroup, ReflectedTypeModelImpl(pkg, className, fields, typeHasFieldsObject = true, baseTypeMapper))

@VisibleForTest
fun KotlinGRTFilesBuilder.fieldsObjectGenForArguments(
    className: String,
    fields: Iterable<ViaductSchema.HasDefaultValue>
): STContents = STContents(fieldsSTGroup, ReflectedTypeModelImpl(pkg, className, fields, typeHasFieldsObject = true, baseTypeMapper, pathToParentObject = null))

private interface ReflectedTypeModel {
    /** GraphQL name of this type */
    val name: String

    /** fully-qualified classname of the corresponding GRT */
    val grtFqName: String

    /** fully-qualified classname of this type descriptor */
    val reflectedTypeFqName: String

    /** does this type define a Fields object */
    val typeHasFieldsObject: Boolean

    /** fields on this type, if any */
    val fields: List<ReflectedFieldModel>
}

private interface ReflectedFieldModel {
    /** GraphQL name of this field */
    val name: String

    /** the escaped GraphQL name of this field, suitable for use as a kotlin identifier */
    val escapedName: String

    /** the reflected type on which this field is mounted */
    val containingType: ReflectedTypeModel

    /** does the type of this field have a reflection */
    val typeHasReflection: Boolean

    /** the kotlin type of this field, eg "List<viaduct.generated.Node?>?" */
    val kotlinType: String

    /** the kotlin type of this field without wrappers, eg "viaduct.generated.Node" */
    val unwrappedKotlinType: String

    /** If [typeHasReflection], then the fully qualified name of the reflected type that describing this fields type */
    val reflectedTypeFqName: String?

    /** true if this field should be emitted as a RootObjectField (root type, object, non-list) */
    val isRootObjectField: Boolean

    /** FQN of the Arguments type for root composite fields, null otherwise */
    val argumentsTypeFqName: String?

    /** Comma-separated quoted path elements for rootFieldPath, null if not a root composite field */
    val rootFieldPathLiteral: String?
}

private val typeST =
    stTemplate(
        """
    @OptIn(viaduct.apiannotations.InternalApi::class)
    object ${cfg.REFLECTION_NAME} : ${cfg.REFLECTED_TYPE}\<<mdl.grtFqName>\> {
        override final val name = "<mdl.name>"
        override final val kcls = <mdl.grtFqName>::class
    }
"""
    )

private val fieldsST =
    stTemplate(
        """
    <if(mdl.typeHasFieldsObject)>
    object Fields : ${cfg.REFLECTED_TYPE_FIELDS}\<<mdl.grtFqName>\> {
        <mdl.fields:field(); separator="\n">
    }
    <endif>
"""
    )

private val fieldST =
    stTemplate(
        "field(mdl)",
        """
    <if(mdl.rootObjectField)>
        final val <mdl.escapedName>: ${cfg.REFLECTED_ROOT_OBJECT_FIELD}\<<\\>
            <mdl.containingType.grtFqName>, <\\>
            <mdl.unwrappedKotlinType>, <\\>
            <mdl.argumentsTypeFqName><\\>
        > =
            ${cfg.REFLECTED_ROOT_OBJECT_FIELD_IMPL}(<\\>
                "<mdl.name>", <\\>
                <mdl.containingType.reflectedTypeFqName>, <\\>
                <mdl.reflectedTypeFqName>, <\\>
                listOf(<mdl.rootFieldPathLiteral>)<\\>
            )
    <elseif(mdl.typeHasReflection)>
        final val <mdl.escapedName>: ${cfg.REFLECTED_COMPOSITE_FIELD}\<<\\>
            <mdl.containingType.grtFqName>, <\\>
            <mdl.unwrappedKotlinType><\\>
        > =
            ${cfg.REFLECTED_COMPOSITE_FIELD_IMPL}(<\\>
                "<mdl.name>", <\\>
                <mdl.containingType.reflectedTypeFqName>, <\\>
                <mdl.reflectedTypeFqName><\\>
            )
    <else>
        final val <mdl.escapedName>: ${cfg.REFLECTED_FIELD}\<<\\>
            <mdl.containingType.grtFqName><\\>
        > =
            ${cfg.REFLECTED_FIELD_IMPL}(<\\>
                "<mdl.name>", <\\>
                <mdl.containingType.reflectedTypeFqName><\\>
            )
    <endif>
"""
    )

private val stGroup = typeST
private val fieldsSTGroup = fieldsST + fieldST

private class ReflectedTypeModelImpl(
    val pkg: String,
    override val name: String,
    val defFields: Iterable<ViaductSchema.HasDefaultValue>,
    override val typeHasFieldsObject: Boolean,
    val baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    val pathToParentObject: List<String>? = null
) : ReflectedTypeModel {
    override val grtFqName: String = "$pkg.$name"
    override val reflectedTypeFqName: String = "$pkg.$name.${cfg.REFLECTION_NAME}"
    override val fields: List<ReflectedFieldModel>
        get() {
            val fieldModels = defFields
                .map { ReflectedFieldModelImpl(pkg, this, it, baseTypeMapper, pathToParentObject) }
            return listOf(__typename(this)) + fieldModels
        }
}

private class __typename(override val containingType: ReflectedTypeModel) : ReflectedFieldModel {
    override val name: String = "__typename"
    override val escapedName: String = name
    override val typeHasReflection: Boolean = false
    override val kotlinType: String = "kotlin.String"
    override val unwrappedKotlinType: String = "kotlin.String"
    override val reflectedTypeFqName: String = "null"
    override val isRootObjectField: Boolean = false
    override val argumentsTypeFqName: String? = null
    override val rootFieldPathLiteral: String? = null
}

private class ReflectedFieldModelImpl(
    pkg: String,
    override val containingType: ReflectedTypeModel,
    field: ViaductSchema.HasDefaultValue,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    pathToParentObject: List<String>?
) : ReflectedFieldModel {
    private val kmPkg = JavaName(pkg).asKmName

    // Root-object fields exist only for output-object fields (ViaductSchema.Field). Argument
    // "fields" (FieldArg) are never root-object fields, so this stays null for Arguments GRTs.
    private val outputField: ViaductSchema.Field? = field as? ViaductSchema.Field

    override val name: String = field.name
    override val escapedName: String = getEscapedFieldName(field.name)
    override val typeHasReflection: Boolean = field.type.baseTypeDef.hasReflectedType
    override val kotlinType: String = field.kmType(kmPkg, baseTypeMapper).kotlinTypeString
    override val unwrappedKotlinType: String = field.type.baseTypeDef.asTypeExpr()
        .kmType(kmPkg, baseTypeMapper, field, isInput = false, useSchemaValueType = false)
        .kotlinTypeString
        .trimEnd('?')

    override val reflectedTypeFqName: String? =
        if (typeHasReflection) {
            "$pkg.${field.type.baseTypeDef.name}.${cfg.REFLECTION_NAME}"
        } else {
            null
        }

    // A root-object field is always an output field, so this is non-null exactly when
    // [isRootObjectField] is true.
    private val rootObjectField: ViaductSchema.Field? =
        outputField?.takeIf { it.isRootObjectFieldEligible(pathToParentObject) }

    override val isRootObjectField: Boolean = rootObjectField != null

    override val argumentsTypeFqName: String? = rootObjectField?.let {
        if (it.hasArgs) "$pkg.${cfg.argumentTypeName(it)}" else cfg.ARGUMENTS_NO_ARGUMENTS.toString().replace('$', '.')
    }

    override val rootFieldPathLiteral: String? =
        if (isRootObjectField) {
            (pathToParentObject!! + field.name).joinToString(", ") { "\"$it\"" }
        } else {
            null
        }
}
