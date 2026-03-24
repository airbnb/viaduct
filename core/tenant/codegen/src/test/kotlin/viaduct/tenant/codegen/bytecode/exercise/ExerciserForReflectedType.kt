package viaduct.tenant.codegen.bytecode.exercise

import kotlin.reflect.KClass
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.hasReflectedType

/** Exercise the "Reflection" object nested in each GRT */
internal fun Exerciser.exerciseReflectionObject(
    container: KClass<*>,
    def: ViaductSchema.TypeDef
) {
    check.withNestedClass(container, cfg.REFLECTION_NAME, "REFLECTION_TYPE") { typeCls ->
        check.withObjectInstance(typeCls, "REFLECTION_NOT_OBJECT") { type ->
            exerciseType("REFLECTION_TYPE", def, type)
        }
    }
}

/** Exercise a `Type` instance */
private fun Exerciser.exerciseType(
    prefix: String,
    exp: ViaductSchema.TypeDef,
    type: Any
) {
    if (!check.isInstanceOf(Type::class, type, "${prefix}_TYPE_NOT_TYPE")) {
        return
    }

    type as Type<*>
    check.isEqualTo(exp.name, type.name, "${prefix}_TYPE_NAME")

    if (exp is ViaductSchema.Record) {
        check.withNestedClass(type::class, "Fields", "${prefix}_FIELDS") { fieldsCls ->
            check.withObjectInstance(fieldsCls, "${prefix}_FIELDS_IS_OBJECT") { fields ->
                exerciseFieldsObject(prefix, exp, fields)
            }
        }
    }
}

/** Exercise the `Fields` object of a type */
private fun Exerciser.exerciseFieldsObject(
    prefix: String,
    exp: ViaductSchema.TypeDef,
    fields: Any
) {
    check.withProperty<Field<*>>(fields, "__typename", "${prefix}_FIELDS_TYPENAME") { field ->
        exerciseField(prefix, "__typename", exp.name, null, field)
    }

    if (exp is ViaductSchema.Record) {
        exp.fields.forEach { expField ->
            check.withProperty<Field<*>>(fields, expField.name, "${prefix}_FIELDS_FIELD") { field ->
                exerciseField(prefix, expField, field)
            }
        }
    }
}

/** Exercise a `Field` value */
private fun Exerciser.exerciseField(
    prefix: String,
    exp: ViaductSchema.Field,
    field: Any
) {
    exerciseField(
        prefix = prefix,
        expName = exp.name,
        expContainingName = exp.containingDef.name,
        expBaseType = exp.type.baseTypeDef,
        field
    )
}

/** Exercise a `Field` value */
private fun Exerciser.exerciseField(
    prefix: String,
    expName: String,
    expContainingName: String,
    expBaseType: ViaductSchema.TypeDef?,
    field: Any
) {
    if (check.isInstanceOf(Field::class, field, "${prefix}_FIELD_NOT_FIELD")) {
        field as Field<*>
        check.isEqualTo(expName, field.name, "${prefix}_FIELD_NAME")
        check.isEqualTo(expContainingName, field.containingType.name, "${prefix}_FIELD_CONTAINING_TYPE")
    }

    if (expBaseType?.hasReflectedType == true) {
        if (!check.isInstanceOf(CompositeField::class, field, "${prefix}_FIELD_NOT_COMPOSITEFIELD")) {
            return
        }

        field as CompositeField<*, *>
        check.isEqualTo(expBaseType.name, field.type.name, "${prefix}_FIELD_TYPE")
    } else {
        check.isFalse(field is CompositeField<*, *>, "${prefix}_FIELD_IS_COMPOSITE")
    }
}
