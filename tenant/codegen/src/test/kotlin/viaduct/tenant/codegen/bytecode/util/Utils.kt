package viaduct.tenant.codegen.bytecode.util

import org.junit.jupiter.api.Assertions.assertEquals
import viaduct.codegen.km.kotlinTypeString
import viaduct.codegen.utils.JavaName
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.kmType

const val expectedPkg = "pkg"

fun ViaductExtendedSchema.TypeDef.assertKotlinTypeString(
    expected: String,
    isInput: Boolean = false,
    pkg: String = expectedPkg,
    baseTypeMapper: BaseTypeMapper = ViaductBaseTypeMapper(ViaductExtendedSchema.Empty)
) = asTypeExpr().assertKotlinTypeString(expected, field = null, isInput = isInput, pkg = pkg, baseTypeMapper = baseTypeMapper)

fun ViaductExtendedSchema.HasDefaultValue.assertKotlinTypeString(
    expected: String,
    isInput: Boolean = false,
    pkg: String = expectedPkg,
    baseTypeMapper: BaseTypeMapper = ViaductBaseTypeMapper(ViaductExtendedSchema.Empty)
) = type.assertKotlinTypeString(expected, field = this, isInput = isInput, pkg = pkg, baseTypeMapper = baseTypeMapper)

fun ViaductExtendedSchema.TypeExpr.assertKotlinTypeString(
    expected: String,
    field: ViaductExtendedSchema.HasDefaultValue?,
    isInput: Boolean = false,
    useSchemaValueType: Boolean = false,
    pkg: String = expectedPkg,
    baseTypeMapper: BaseTypeMapper = ViaductBaseTypeMapper(ViaductExtendedSchema.Empty)
) {
    assertEquals(
        expected,
        kmType(JavaName(pkg).asKmName, baseTypeMapper, field = field, isInput = isInput, useSchemaValueType = useSchemaValueType).kotlinTypeString
    )
}

fun ViaductExtendedSchema.typedef(type: String): ViaductExtendedSchema.TypeDef = types[type]!!

fun ViaductExtendedSchema.expr(
    type: String,
    field: String? = null
): ViaductExtendedSchema.TypeExpr =
    typedef(type).let { t ->
        if (field == null) {
            t.asTypeExpr()
        } else {
            (t as ViaductExtendedSchema.Record).field(field)!!.type
        }
    }

fun ViaductExtendedSchema.field(
    type: String,
    field: String
): ViaductExtendedSchema.Field = (types[type]!! as ViaductExtendedSchema.Record).field(field)!!
