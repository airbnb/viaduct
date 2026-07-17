package viaduct.tenant.codegen.bytecode

import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmVariance
import kotlinx.metadata.Modality
import kotlinx.metadata.isNullable
import viaduct.codegen.km.CustomClassBuilder
import viaduct.codegen.km.EnumClassBuilder
import viaduct.codegen.km.KmPropertyBuilder
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.codegen.utils.name
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.baseTypeKmType
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.hasFieldsObject
import viaduct.tenant.codegen.bytecode.config.hasReflectedType
import viaduct.tenant.codegen.bytecode.config.isRootObjectFieldEligible
import viaduct.tenant.codegen.bytecode.config.pathFromQueryRoot
import viaduct.tenant.codegen.bytecode.config.reflectedFields

internal fun GRTClassFilesBuilder.reflectedTypeGen(
    def: ViaductSchema.TypeDef,
    container: CustomClassBuilder
) = reflectedTypeGen(def.name, container)

internal fun GRTClassFilesBuilder.reflectedTypeGen(
    def: ViaductSchema.TypeDef,
    container: EnumClassBuilder
) {
    ReflectedTypeBuilder(
        this,
        def.name,
        container.nestedClassBuilder(JavaIdName(cfg.REFLECTION_NAME), kind = ClassKind.OBJECT)
    ).build()
}

/**
 * Reflection generation from a simple GRT class name ([typeName]). Used directly for Arguments GRTs
 * (e.g. `Query_Order_Arguments`), which have no backing [ViaductSchema.TypeDef].
 */
internal fun GRTClassFilesBuilder.reflectedTypeGen(
    typeName: String,
    container: CustomClassBuilder
) {
    ReflectedTypeBuilder(
        this,
        typeName,
        container.nestedClassBuilder(JavaIdName(cfg.REFLECTION_NAME), kind = ClassKind.OBJECT)
    ).build()
}

private class ReflectedTypeBuilder(
    override val grtClassFilesBuilder: GRTClassFilesBuilder,
    private val typeName: String,
    private val typeBuilder: CustomClassBuilder,
) : MirrorUtils {
    private val grtType: KmType = typeName.kmFQN(grtClassFilesBuilder.pkg).asType()

    init {
        typeBuilder.addSupertype(
            cfg.REFLECTED_TYPE.asKmName.asType().also {
                it.arguments += KmTypeProjection(KmVariance.INVARIANT, grtType)
            }
        )
    }

    fun build() {
        buildNameProperty()
        buildKclsProperty()
    }

    /**
     * Build a `name: String` property
     * @see [viaduct.api.reflect.Type.name]
     */
    private fun buildNameProperty() {
        typeBuilder.addProperty(
            KmPropertyBuilder(
                name = JavaIdName("name"),
                type = Km.STRING.asType(),
                inputType = Km.STRING.asType(),
                isVariable = false,
                constructorProperty = true,
            ).also {
                it.hasConstantValue(true)
                it.propertyModality(Modality.FINAL)
                it.getterBody(
                    """{return "$typeName";}"""
                )
            }
        )
    }

    private fun buildKclsProperty() {
        Km.KCLASS.asType().also {
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, grtType)
        }.let { type ->
            typeBuilder.addProperty(
                KmPropertyBuilder(
                    name = JavaIdName("kcls"),
                    type = type,
                    inputType = type,
                    isVariable = false,
                    constructorProperty = true,
                ).also {
                    it.propertyModality(Modality.FINAL)
                    it.getterBody(
                        // Reflection.getOrCreateKotlinClass(Foo.class)
                        buildString {
                            append("{")
                            append("return kotlin.jvm.internal.Reflection.getOrCreateKotlinClass(")
                            append(grtType.name.asJavaBinaryName)
                            append(".class")
                            append(");")
                            append("}")
                        }
                    )
                }
            )
        }
    }
}

internal fun GRTClassFilesBuilder.fieldsObjectGen(
    def: ViaductSchema.TypeDef,
    container: CustomClassBuilder,
) {
    if (def.hasFieldsObject) {
        FieldsObjectBuilder(
            this,
            container,
            grtType = def.name.kmFQN(this.pkg).asType(),
            containingInstanceExpr = reflectionInstanceExpr(reflectedTypeKmNameForDef(def)),
            fields = def.reflectedFields,
            pathToParentObject = def.pathFromQueryRoot(reverseSchema, schema.queryTypeDef),
        ).build()
    }
}

/**
 * Fields-object generation for an Arguments GRT, driven by its simple class name
 * ([argumentsSimpleName]) and its field-argument list. Arguments always get a Fields object; no
 * argument is a root-object field, so [pathToParentObject] is null.
 */
internal fun GRTClassFilesBuilder.fieldsObjectGen(
    argumentsSimpleName: String,
    fields: Iterable<ViaductSchema.HasDefaultValue>,
    container: CustomClassBuilder,
) {
    val reflectionKmName = argumentsSimpleName.kmFQN(this.pkg).append(".${cfg.REFLECTION_NAME}")
    FieldsObjectBuilder(
        this,
        container,
        grtType = argumentsSimpleName.kmFQN(this.pkg).asType(),
        containingInstanceExpr = reflectionInstanceExpr(reflectionKmName),
        fields = fields,
        pathToParentObject = null,
    ).build()
}

private class FieldsObjectBuilder(
    override val grtClassFilesBuilder: GRTClassFilesBuilder,
    container: CustomClassBuilder,
    private val grtType: KmType,
    private val containingInstanceExpr: String,
    private val fields: Iterable<ViaductSchema.HasDefaultValue>,
    private val pathToParentObject: List<String>?,
) : MirrorUtils {
    private val fieldsBuilder =
        container.nestedClassBuilder(
            simpleName = JavaIdName("Fields"),
            kind = ClassKind.OBJECT
        ).also {
            // Fields implements TypeFields<T>
            it.addSupertype(
                cfg.REFLECTED_TYPE_FIELDS.asKmName.asType().also { type ->
                    type.arguments += KmTypeProjection(KmVariance.INVARIANT, grtType)
                }
            )
        }

    fun build() {
        buildSimpleFieldProperty("__typename")
        fields.forEach { f ->
            grtClassFilesBuilder.addSchemaGRTReference(f.type.baseTypeDef)

            val unwrappedType = f.baseTypeKmType(grtClassFilesBuilder.pkg, grtClassFilesBuilder.baseTypeMapper).apply {
                isNullable = false
            }
            val reflectedType = f.type.baseTypeDef.takeIf { it.hasReflectedType }
            // Root-object fields exist only for output-object fields (ViaductSchema.Field); argument
            // "fields" (FieldArg) are never root-object fields.
            val outputField = f as? ViaductSchema.Field

            if (reflectedType != null && outputField?.isRootObjectFieldEligible(pathToParentObject) == true) {
                buildRootObjectFieldProperty(outputField, unwrappedType, reflectedType)
            } else if (reflectedType != null) {
                buildCompositeFieldProperty(f.name, unwrappedType, reflectedType)
            } else {
                buildSimpleFieldProperty(f.name)
            }
        }
    }

    /** build a [viaduct.api.reflect.Field] property for a non-composite field */
    private fun buildSimpleFieldProperty(name: String) {
        val fieldType = cfg.REFLECTED_FIELD.asKmName.asType().also {
            // Field<Parent: GRT>
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, grtType)
        }
        fieldsBuilder.addProperty(
            KmPropertyBuilder(
                name = JavaIdName(name),
                type = fieldType,
                inputType = fieldType,
                isVariable = false,
                constructorProperty = true
            ).also {
                it.getterBody(
                    buildString {
                        // class Field<>(
                        //   val name: String,
                        //   val containingType: Type<P>
                        // )
                        append("{")
                        append("return new ${cfg.REFLECTED_FIELD_IMPL}(")
                        // name
                        append("\"${name}\",")
                        // containingType
                        append(containingInstanceExpr)
                        append(");\n")
                        append("}")
                    }
                )
            }
        )
    }

    /** build a [viaduct.api.reflect.CompositeField] property for a field with a [viaduct.api.types.CompositeOutput] type  */
    private fun buildCompositeFieldProperty(
        name: String,
        unwrappedFieldType: KmType,
        reflectedType: ViaductSchema.TypeDef
    ) {
        val fieldType = cfg.REFLECTED_COMPOSITE_FIELD.asKmName.asType().also {
            // CompositeField<Parent: GRT, UnwrappedType: GRT>
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, grtType)
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, unwrappedFieldType)
        }
        fieldsBuilder.addProperty(
            KmPropertyBuilder(
                name = JavaIdName(name),
                type = fieldType,
                inputType = fieldType,
                isVariable = false,
                constructorProperty = true
            ).also {
                it.getterBody(
                    buildString {
                        // class CompositeField<>(
                        //   val name: String,
                        //   val containingType: Type<P>,
                        //   val type: Type<*>
                        // )
                        append("{\n")
                        append("return new ${cfg.REFLECTED_COMPOSITE_FIELD_IMPL}(\n")
                        // name
                        append("\"${name}\",\n")
                        // containingType
                        append(containingInstanceExpr)
                        append(",\n")
                        // type
                        append(reflectedType.instanceExpr)
                        append("\n);\n")
                        append("}")
                    }
                )
            }
        )
    }

    /** build a [viaduct.api.reflect.RootObjectField] property for a root type field with a composite return type */
    private fun buildRootObjectFieldProperty(
        field: ViaductSchema.Field,
        unwrappedFieldType: KmType,
        reflectedType: ViaductSchema.TypeDef
    ) {
        val argsKmType = if (field.hasArgs) {
            KmName("${grtClassFilesBuilder.pkg}/${cfg.argumentTypeName(field)}".replace('.', '/')).asType()
        } else {
            cfg.ARGUMENTS_NO_ARGUMENTS.asKmName.asType()
        }

        val fieldType = cfg.REFLECTED_ROOT_OBJECT_FIELD.asKmName.asType().also {
            // RootObjectField<Parent: GRT, UnwrappedType: Object, A: Arguments>
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, grtType)
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, unwrappedFieldType)
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, argsKmType)
        }
        fieldsBuilder.addProperty(
            KmPropertyBuilder(
                name = JavaIdName(field.name),
                type = fieldType,
                inputType = fieldType,
                isVariable = false,
                constructorProperty = true
            ).also {
                it.getterBody(
                    buildString {
                        val fullPath = pathToParentObject!! + field.name
                        append("{\n")
                        append("java.util.ArrayList __path = new java.util.ArrayList(${fullPath.size});\n")
                        for (segment in fullPath) {
                            append("__path.add(\"$segment\");\n")
                        }
                        append("return new ${cfg.REFLECTED_ROOT_OBJECT_FIELD_IMPL}(\n")
                        // name
                        append("\"${field.name}\",\n")
                        // containingType
                        append(containingInstanceExpr)
                        append(",\n")
                        // type
                        append(reflectedType.instanceExpr)
                        append(",\n")
                        // rootFieldPath
                        append("java.util.Collections.unmodifiableList(__path)")
                        append("\n);\n")
                        append("}")
                    }
                )
            }
        )
    }
}

private fun GRTClassFilesBuilder.reflectedTypeKmNameForDef(def: ViaductSchema.TypeDef): KmName =
    def.asTypeExpr().baseTypeKmType(this.pkg, this.baseTypeMapper, null, false).name.append(".${cfg.REFLECTION_NAME}")

/**
 * Returns an expression that points to the object instance of the reflective type named by
 * [reflectionKmName].
 *
 * A more straight-forward way of doing this would be `DefName$Reflection.INSTANCE`. That fails when
 * the type is in another build shard, in which case the class for `ViaductMirror$DefName` will be
 * an external class that allows some compilation but does not allow access to its members, such as
 * the `INSTANCE` field.
 *
 * This works around it by loading the kclass via kotlin Reflection, which provides a
 * `getObjectInstance` method that works for types in any build shard.
 */
private fun reflectionInstanceExpr(reflectionKmName: KmName): String =
    buildString {
        append("(${cfg.REFLECTED_TYPE}) ") // cast objectInstance to a ReflectedType
        append("kotlin.jvm.internal.Reflection.getOrCreateKotlinClass(")
        append(reflectionKmName.asType().name.asJavaBinaryName)
        append(".class")
        append(").getObjectInstance()")
    }

private interface MirrorUtils {
    val grtClassFilesBuilder: GRTClassFilesBuilder

    val ViaductSchema.TypeDef.grtType: KmType
        get() = name.kmFQN(grtClassFilesBuilder.pkg).asType()

    val ViaductSchema.TypeDef.instanceExpr: String
        get() = reflectionInstanceExpr(grtClassFilesBuilder.reflectedTypeKmNameForDef(this))
}
