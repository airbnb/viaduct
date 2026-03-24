@file:Suppress("MatchingDeclarationName")

package viaduct.codegen.utils

import kotlinx.metadata.KmAnnotation
import kotlinx.metadata.KmAnnotationArgument
import kotlinx.metadata.KmClassifier
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeParameter
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmValueParameter

// KmType extensions and related utilities
object Km {
    val ANY = KmName("kotlin/Any")
    val ARRAY = KmName("kotlin/Array")
    val BOOLEAN = KmName("kotlin/Boolean")
    val BYTE = KmName("kotlin/Byte")
    val CHAR = KmName("kotlin/Char")
    val DOUBLE = KmName("kotlin/Double")
    val ENUM = KmName("kotlin/Enum")
    val FLOAT = KmName("kotlin/Float")
    val INT = KmName("kotlin/Int")
    val KCLASS = KmName("kotlin/reflect/KClass")
    val LIST = KmName("kotlin/collections/List")
    val LONG = KmName("kotlin/Long")
    val MAP = KmName("kotlin/collections/Map")
    val MUTABLE_MAP = KmName("kotlin/collections/MutableMap")
    val SHORT = KmName("kotlin/Short")
    val STRING = KmName("kotlin/String")
    val UNIT = KmName("kotlin/Unit")
    val NOT_NULL = KmName("org/jetbrains/annotations/NotNull")
}

val KmType.name: KmName get() =
    when (classifier) {
        is KmClassifier.Class -> KmName((classifier as KmClassifier.Class).name)
        else -> throw IllegalArgumentException("Can't handle $this")
    }

val KmName.defaultImpls: KmName get() = append(".$DEFAULT_IMPLS")

/**
 * Used for annotation runtime visibility
 */
const val VISIBLE = true
const val INVISIBLE = false

/** Used for interfaces with default implementations */
const val DEFAULT_IMPLS = "DefaultImpls"

val KmType.refs: Set<KmName> get() = setOf(this.name) + arguments.flatMap { it.refs }
val KmValueParameter.refs: Set<KmName> get() = type.refs
val KmConstructor.refs: Set<KmName> get() = valueParameters.flatMap { it.refs }.toSet()
val KmTypeProjection.refs: Set<KmName> get() = type?.refs ?: emptySet()
val KmTypeParameter.refs: Set<KmName> get() = upperBounds.flatMap { it.refs }.toSet()
val KmFunction.refs: Set<KmName> get() =
    valueParameters.flatMap { it.refs }.toSet() +
        typeParameters.flatMap { it.refs }.toSet() +
        returnType.refs

val KmAnnotationArgument.refs: Set<KmName> get() = when (this) {
    is KmAnnotationArgument.AnnotationValue -> this.annotation.refs
    is KmAnnotationArgument.KClassValue -> setOf(KmName(this.className))
    is KmAnnotationArgument.ArrayKClassValue -> setOf(KmName(this.className))
    is KmAnnotationArgument.ArrayValue -> this.elements.flatMap { it.refs }.toSet()
    is KmAnnotationArgument.EnumValue -> setOf(KmName(this.enumClassName))
    else -> emptySet()
}

val Set<Pair<KmAnnotation, Boolean>>.refs: Set<KmName> get() =
    flatMap { it.first.refs }.toSet()
val KmAnnotation.refs: Set<KmName> get() =
    this.arguments.flatMap { it.value.refs }.toSet() + KmName(this.className)
