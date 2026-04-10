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
import viaduct.tenant.codegen.bytecode.config.hasReflectedType
import viaduct.tenant.codegen.bytecode.config.isRootCompositeFieldEligible
import viaduct.tenant.codegen.bytecode.config.kmType

@VisibleForTest
fun KotlinGRTFilesBuilder.reflectedTypeGen(def: ViaductSchema.TypeDef): STContents = STContents(stGroup, ReflectedTypeModelImpl(pkg, def, baseTypeMapper))

@VisibleForTest
fun KotlinGRTFilesBuilder.fieldsObjectGen(
    def: ViaductSchema.TypeDef,
    isRootField: Boolean = false
): STContents = STContents(fieldsSTGroup, ReflectedTypeModelImpl(pkg, def, baseTypeMapper, isRootField))

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

    /** true if this field should be emitted as a RootCompositeField (root type, composite, non-list) */
    val isRootCompositeField: Boolean

    /** FQN of the Arguments type for root composite fields, null otherwise */
    val argumentsTypeFqName: String?
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
    <if(mdl.rootCompositeField)>
        final val <mdl.escapedName>: ${cfg.REFLECTED_ROOT_COMPOSITE_FIELD}\<<\\>
            <mdl.containingType.grtFqName>, <\\>
            <mdl.unwrappedKotlinType>, <\\>
            <mdl.argumentsTypeFqName><\\>
        > =
            ${cfg.REFLECTED_ROOT_COMPOSITE_FIELD_IMPL}(<\\>
                "<mdl.name>", <\\>
                <mdl.containingType.reflectedTypeFqName>, <\\>
                <mdl.reflectedTypeFqName><\\>
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
    val def: ViaductSchema.TypeDef,
    val baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    val isRootField: Boolean = false
) : ReflectedTypeModel {
    override val name: String = def.name
    override val grtFqName: String = "$pkg.$name"
    override val reflectedTypeFqName: String = "$pkg.${def.name}.${cfg.REFLECTION_NAME}"
    override val typeHasFieldsObject: Boolean = def is ViaductSchema.Union || def is ViaductSchema.Record
    override val fields: List<ReflectedFieldModel>
        get() {
            val defFields = ((def as? ViaductSchema.Record)?.fields ?: emptyList())
                .map { ReflectedFieldModelImpl(pkg, this, it, baseTypeMapper, isRootField) }
            return listOf(__typename(this)) + defFields
        }
}

private class __typename(override val containingType: ReflectedTypeModel) : ReflectedFieldModel {
    override val name: String = "__typename"
    override val escapedName: String = name
    override val typeHasReflection: Boolean = false
    override val kotlinType: String = "kotlin.String"
    override val unwrappedKotlinType: String = "kotlin.String"
    override val reflectedTypeFqName: String = "null"
    override val isRootCompositeField: Boolean = false
    override val argumentsTypeFqName: String? = null
}

private class ReflectedFieldModelImpl(
    pkg: String,
    override val containingType: ReflectedTypeModel,
    field: ViaductSchema.Field,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    isRootField: Boolean
) : ReflectedFieldModel {
    private val kmPkg = JavaName(pkg).asKmName

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

    override val isRootCompositeField: Boolean =
        field.isRootCompositeFieldEligible(isRootField)

    override val argumentsTypeFqName: String? = when {
        !isRootCompositeField -> null
        field.hasArgs -> "$pkg.${cfg.argumentTypeName(field)}"
        else -> cfg.ARGUMENTS_NO_ARGUMENTS.toString().replace('$', '.')
    }
}
