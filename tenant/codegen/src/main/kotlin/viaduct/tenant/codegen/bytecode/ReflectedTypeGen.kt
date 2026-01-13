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
import viaduct.tenant.codegen.bytecode.config.hasReflectedType

internal fun GRTClassFilesBuilder.reflectedTypeGen(
    def: ViaductSchema.TypeDef,
    container: CustomClassBuilder
) {
    ReflectedTypeBuilder(
        this,
        def,
        container.nestedClassBuilder(JavaIdName(cfg.REFLECTION_NAME), kind = ClassKind.OBJECT)
    ).build()
}

internal fun GRTClassFilesBuilder.reflectedTypeGen(
    def: ViaductSchema.TypeDef,
    container: EnumClassBuilder
) {
    ReflectedTypeBuilder(
        this,
        def,
        container.nestedClassBuilder(JavaIdName(cfg.REFLECTION_NAME), kind = ClassKind.OBJECT)
    ).build()
}

private class ReflectedTypeBuilder(
    override val grtClassFilesBuilder: GRTClassFilesBuilder,
    private val def: ViaductSchema.TypeDef,
    private val typeBuilder: CustomClassBuilder,
) : MirrorUtils {
    init {
        typeBuilder.addSupertype(
            cfg.REFLECTED_TYPE.asKmName.asType().also {
                it.arguments += KmTypeProjection(KmVariance.INVARIANT, def.grtType)
            }
        )
    }

    fun build() {
        buildNameProperty()
        buildKclsProperty()
        if (def is ViaductSchema.Record || def is ViaductSchema.Union) {
            FieldsObjectBuilder(grtClassFilesBuilder, typeBuilder, def)
                .build()
        }
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
                    """{return "${def.name}";}"""
                )
            }
        )
    }

    private fun buildKclsProperty() {
        Km.KCLASS.asType().also {
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, def.grtType)
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
                            append(def.grtType.name.asJavaBinaryName)
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

private class FieldsObjectBuilder(
    override val grtClassFilesBuilder: GRTClassFilesBuilder,
    container: CustomClassBuilder,
    private val def: ViaductSchema.TypeDef
) : MirrorUtils {
    private val fieldsBuilder =
        container.nestedClassBuilder(
            simpleName = JavaIdName("Fields"),
            kind = ClassKind.OBJECT
        )

    fun build() {
        buildSimpleFieldProperty("__typename")
        if (def is ViaductSchema.Record) {
            def.fields.forEach { f ->
                grtClassFilesBuilder.addSchemaGRTReference(f.type.baseTypeDef)

                val unwrappedType = f.baseTypeKmType(grtClassFilesBuilder.pkg, grtClassFilesBuilder.baseTypeMapper).apply {
                    isNullable = false
                }
                val reflectedType = f.type.baseTypeDef.takeIf { it.hasReflectedType }

                if (reflectedType != null) {
                    buildCompositeFieldProperty(f.name, unwrappedType, reflectedType)
                } else {
                    buildSimpleFieldProperty(f.name)
                }
            }
        }
    }

    /** build a [viaduct.api.reflect.Field] property for a non-composite field */
    private fun buildSimpleFieldProperty(name: String) {
        val fieldType = cfg.REFLECTED_FIELD.asKmName.asType().also {
            // Field<Parent: GRT>
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, def.grtType)
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
                        append(def.instanceExpr)
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
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, def.grtType)
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
                        append(def.instanceExpr)
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
}

private fun GRTClassFilesBuilder.reflectedTypeKmNameForDef(def: ViaductSchema.TypeDef): KmName =
    def.asTypeExpr().baseTypeKmType(this.pkg, this.baseTypeMapper, null).name.append(".${cfg.REFLECTION_NAME}")

private fun GRTClassFilesBuilder.reflectedTypeForDef(def: ViaductSchema.TypeDef): KmType = reflectedTypeKmNameForDef(def).asType()

private interface MirrorUtils {
    val grtClassFilesBuilder: GRTClassFilesBuilder

    val ViaductSchema.TypeDef.grtType: KmType
        get() = name.kmFQN(grtClassFilesBuilder.pkg).asType()

    val ViaductSchema.TypeDef.instanceExpr: String
        get() = let { def ->
            // This getter returns an expression that points to the object instance of the reflective type for
            // the provided Def.
            // A more straight-forward way of doing this would be:
            //   DefName$Reflection.INSTANCE
            // This fails when Def is in another build shard, in which case the class for ViaductMirror$DefName
            // will be an external class that allows some compilation but does not allow access to its members,
            // such as the `INSTANCE field`
            //
            // This getter works around this by loading the kclass via kotlin Reflection, which provides a
            // `getObjectInstance` method that should work for types in any build shard
            buildString {
                append("(${cfg.REFLECTED_TYPE}) ") // cast objectInstance to a ReflectedType
                append("kotlin.jvm.internal.Reflection.getOrCreateKotlinClass(")
                append(grtClassFilesBuilder.reflectedTypeForDef(def).name.asJavaBinaryName)
                append(".class")
                append(").getObjectInstance()")
            }
        }
}
