@file:Suppress("MatchingDeclarationName")

package viaduct.codegen.ct

import javassist.bytecode.AccessFlag
import javassist.bytecode.Descriptor
import kotlinx.metadata.KmClass
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmProperty
import kotlinx.metadata.KmType
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.isSuspend
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.name

// Constants
object Ct {
    val BOOLEAN = CtName("boolean")
    val BYTE = CtName("byte")
    val CHAR = CtName("char")
    val DOUBLE = CtName("double")
    val FLOAT = CtName("float")
    val INT = CtName("int")
    val LONG = CtName("long")
    val OBJECT = CtName("java.lang.Object")
    val SHORT = CtName("short")
    val VOID = CtName("void")

    val DEFAULT_MARKER = CtName("kotlin.jvm.internal.DefaultConstructorMarker")

    const val STATIC_PUBLIC_FINAL: Int = AccessFlag.STATIC or AccessFlag.PUBLIC or AccessFlag.FINAL
}

// KmType extensions and related utilities

val KM_UNIT_TYPE = Km.UNIT.asType()

// KmClass utilities

val KmClass.simpleName get() = name
    .split("/")
    .last()
    .split(".")
    .last()

// KmFunction type-related extensions

val KmFunction.jvmReturnType: KmType get() =
    if (!isSuspend) {
        returnType
    } else {
        Km.ANY.asNullableType()
    }

// KmValueParameter type-related extensions

val KmValueParameter.javaTypeName: CtName get() = type.javaTypeName

// KmProperty type-related extensions

val KmProperty.javaTypeName: CtName get() = returnType.javaTypeName

val KmProperty.fieldJvmDesc: String get() = returnType.jvmDesc

// KmConstructor type-related extensions

val KmConstructor.jvmDesc: String get() = jvmMethodDesc(valueParameters, KM_UNIT_TYPE)

// *** Below are helper functions for other utility functions *** //

val KmType.javaTypeName: CtName get() =
    javaPrimitiveName ?: javaBoxedTypeName

val KmType.isJavaPrimitive: Boolean get() {
    return javaPrimitiveName != null
}

// *** Below are private helpers for the exported functionality above *** //

private val KmType.javaBoxedTypeName: CtName get() =
    if (name == Km.ARRAY) {
        CtName("${arguments[0].type!!.javaBoxedTypeName}[]")
    } else {
        CtName(kmToJvmBoxedName(name).replace('/', '.'))
    }

// Descriptor-related helpers

private val KmType.jvmDesc: String get() =
    javaPrimitiveName?.let { Descriptor.of(it.toString()) } ?: jvmBoxedDesc

private val KmType.jvmReturnDesc: String get() =
    if (name == Km.UNIT) {
        Descriptor.of("void")
    } else {
        jvmDesc
    }

private val KmType.jvmBoxedDesc: String get() = // Both
    if (name == Km.ARRAY) {
        "[" + arguments[0].type!!.jvmBoxedDesc
    } else {
        "L${kmToJvmBoxedName(name)};"
    }

internal fun jvmMethodDesc(
    valueParams: List<KmValueParameter>,
    returnType: KmType
): String {
    val result = StringBuffer("(")
    valueParams.forEach { result.append(it.type.jvmDesc) }
    result.append(")")
    result.append(returnType.jvmReturnDesc)
    return result.toString()
}
