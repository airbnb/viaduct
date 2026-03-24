package viaduct.tenant.codegen.bytecode

import viaduct.codegen.utils.JavaBinaryName
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductSchema

/** Assumes that [value] is a valid value for [this] type,
 *  where "valid" means that the value in [value] conforms to the types
 *  of objects we use to represent the type-expression [this].
 *  Returns a string of value expression in CtSyntax.
 *  Note we use string representation for Enum values to avoid
 *  dependency on the Enum classes.
 */
fun ViaductSchema.TypeExpr<*>.valueInCtSyntax(
    value: Any?,
    pkg: KmName
): String = valueInCtSyntax(this, this.listDepth, value, pkg)

/** Boxes a value if necessary.  Call this in a context where
 *  a Javassist expression may not be boxed (typically the
 *  `$n` arguments to a method will not be boxed, for example).
 *  If the equivalent GraphQL type maps to Java primitive in
 *  an unboxed setting, then this function will wrap the
 *  expression in a call to the proper boxing function.
 */
internal fun ViaductSchema.TypeExpr<*>.ctBoxedExpr(unboxedExpr: String): String =
    if (this.isList || this.isNullable) {
        unboxedExpr
    } else {
        when (this.baseTypeDef.name) {
            "Boolean" -> "java.lang.Boolean.valueOf($unboxedExpr)"
            "Byte" -> "java.lang.Byte.valueOf($unboxedExpr)"
            "Float" -> "java.lang.Double.valueOf($unboxedExpr)"
            "Int" -> "java.lang.Integer.valueOf($unboxedExpr)"
            "Long" -> "java.lang.Long.valueOf($unboxedExpr)"
            "Short" -> "java.lang.Short.valueOf($unboxedExpr)"
            else -> unboxedExpr
        }
    }

private fun valueInCtSyntax(
    type: ViaductSchema.TypeExpr<*>,
    depth: Int,
    value: Any?,
    pkg: KmName
): String {
    if (value == null) return "null"

    if (0 < depth) {
        val valList = value as List<*>
        if (valList.isEmpty()) return "Arrays.asList(new Object[0])" // javassist bug (new Object[] {} doesn't work)
        val elements = valList.joinToString {
            valueInCtSyntax(type, depth - 1, it, pkg)
        }
        return "Arrays.asList(new Object[] { $elements })"
    }

    val baseTypeDef = type.baseTypeDef

    if (baseTypeDef is ViaductSchema.Enum) {
        if (value is ViaductSchema.EnumValue) {
            return "\"${value.name}\""
        } else {
            throw IllegalArgumentException("Incorrect value type for Enum: $value")
        }
    }

    when (baseTypeDef.name) {
        "Long" -> {
            if (type.baseTypeNullable) {
                return "${typeToCtSyntax(type.baseTypeDef, boxed = true, pkg)}.valueOf(${value}L)"
            } else {
                return "${value}L"
            }
        }
        "Short" -> {
            if (type.baseTypeNullable) {
                return "${typeToCtSyntax(type.baseTypeDef, boxed = true, pkg)}.valueOf((short)$value)"
            } else {
                return "(short)$value"
            }
        }
        "Int", "Float", "Boolean" -> {
            if (type.baseTypeNullable) {
                return "${typeToCtSyntax(type.baseTypeDef, boxed = true, pkg)}.valueOf($value)"
            } else {
                return value.toString()
            }
        }
        "Date" -> {
            if (value !is java.time.LocalDate) throw IllegalArgumentException("Incorrect value for Date: $value")
            return "java.time.LocalDate.parse(\"$value\")"
        }
        "DateTime" -> {
            if (value !is java.time.Instant) throw IllegalArgumentException("Incorrect value for DateTime: $value")
            return "java.time.Instant.parse(\"$value\")"
        }
        "JSON" -> throw IllegalArgumentException("JSON not supported ($type)")
        "String", "ID" -> return "\"$value\""
        else -> {
            if (baseTypeDef !is ViaductSchema.Input) throw IllegalArgumentException("Cannot generate value for $type")
            if (value !is Map<*, *>) throw IllegalArgumentException("Cannot generate value for $type")
            val args = baseTypeDef.fields.joinToString { it.type.valueInCtSyntax(value[it.name], pkg) }
            val baseType = typeToCtSyntax(type.baseTypeDef, boxed = false, pkg)
            return "new $baseType($args)"
        }
    }
}

private fun typeToCtSyntax(
    typeDef: ViaductSchema.TypeDef,
    boxed: Boolean,
    pkg: KmName
): JavaBinaryName =
    JavaBinaryName(
        when (typeDef.name) {
            "Boolean" -> if (boxed) "java.lang.Boolean" else "boolean"
            "Date" -> "java.time.LocalDate"
            "DateTime" -> "java.time.Instant"
            "Float" -> if (boxed) "java.lang.Double" else "double"
            "ID" -> "java.lang.String"
            "Int" -> if (boxed) "java.lang.Integer" else "int"
            "JSON" -> "java.lang.Object"
            "Long" -> if (boxed) "java.lang.Long" else "long"
            "Short" -> if (boxed) "java.lang.Short" else "short"
            "String" -> "java.lang.String"
            else -> "${pkg.asJavaName}.${typeDef.name}"
        }
    )
