package viaduct.tenant.codegen.bytecode.util

import org.junit.jupiter.api.Assertions.assertEquals
import viaduct.codegen.km.kotlinTypeString
import viaduct.codegen.utils.JavaName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.kmType

const val expectedPkg = "pkg"

fun ViaductSchema.TypeDef.assertKotlinTypeString(
    expected: String,
    isInput: Boolean = false,
    pkg: String = expectedPkg,
    baseTypeMapper: BaseTypeMapper = ViaductBaseTypeMapper(ViaductSchema.Empty)
) = asTypeExpr().assertKotlinTypeString(expected, field = null, isInput = isInput, pkg = pkg, baseTypeMapper = baseTypeMapper)

fun ViaductSchema.HasDefaultValue.assertKotlinTypeString(
    expected: String,
    isInput: Boolean = false,
    pkg: String = expectedPkg,
    baseTypeMapper: BaseTypeMapper = ViaductBaseTypeMapper(ViaductSchema.Empty)
) = type.assertKotlinTypeString(expected, field = this, isInput = isInput, pkg = pkg, baseTypeMapper = baseTypeMapper)

fun ViaductSchema.TypeExpr.assertKotlinTypeString(
    expected: String,
    field: ViaductSchema.HasDefaultValue?,
    isInput: Boolean = false,
    useSchemaValueType: Boolean = false,
    pkg: String = expectedPkg,
    baseTypeMapper: BaseTypeMapper = ViaductBaseTypeMapper(ViaductSchema.Empty)
) {
    assertEquals(
        expected,
        kmType(JavaName(pkg).asKmName, baseTypeMapper, field = field, isInput = isInput, useSchemaValueType = useSchemaValueType).kotlinTypeString
    )
}

fun ViaductSchema.typedef(type: String): ViaductSchema.TypeDef = types[type]!!

fun ViaductSchema.expr(
    type: String,
    field: String? = null
): ViaductSchema.TypeExpr =
    typedef(type).let { t ->
        if (field == null) {
            t.asTypeExpr()
        } else {
            (t as ViaductSchema.Record).field(field)!!.type
        }
    }

fun ViaductSchema.field(
    type: String,
    field: String
): ViaductSchema.Field = (types[type]!! as ViaductSchema.Record).field(field)!!
