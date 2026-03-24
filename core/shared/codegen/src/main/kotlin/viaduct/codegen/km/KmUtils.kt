// KmToCtUtils.kt are easier to purge of Javassist dependencies, thus more likely
// to be integrated into kotlinx-metadata

package viaduct.codegen.km

import kotlinx.metadata.KmClassifier
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.KmVariance
import kotlinx.metadata.isNullable
import viaduct.codegen.ct.isJavaPrimitive
import viaduct.codegen.ct.javaTypeName
import viaduct.codegen.ct.kmToJvmBoxedName
import viaduct.codegen.utils.JavaName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.codegen.utils.name
import viaduct.utils.string.capitalize

/**
 * Returns a string for the checkNotNullParameter call if the parameter should be checked, otherwise returns null.
 * @param type: The KmType of the param
 * @param position: The position of the param, starting with 1
 * @param name: The name of the param
 */
fun checkNotNullParameterExpression(
    type: KmType,
    position: Int,
    name: String
): String? {
    if (type.isNullable || type.isJavaPrimitive) {
        return null
    }
    return "kotlin.jvm.internal.Intrinsics.checkNotNullParameter((Object)$$position, \"$name\");"
}

fun checkNotNullParameterExpressions(params: List<KmValueParameter>) =
    buildString {
        params.forEachIndexed { index, param ->
            checkNotNullParameterExpression(param.type, index + 1, param.name)?.let { append(it) }
        }
    }

/**
 * Returns a string for the checkNotNull call if the value should be checked, otherwise returns null.
 * @param type: The KmType of the value
 * @param toCheck: The value expression to check
 */
fun checkNotNullExpression(
    type: KmType,
    toCheck: String
): String? {
    if (type.isNullable || type.isJavaPrimitive) {
        return null
    }
    return "kotlin.jvm.internal.Intrinsics.checkNotNull((Object)$toCheck, " +
        "\"null cannot be cast to non-null type ${type.name.asJavaName}\");"
}

/**
 * Returns a string that boxes the expression if it has a primitive type, otherwise returns the original
 * expression. Used for suspend functions which have Object return types in Java.
 */
fun boxingExpression(
    type: KmType,
    expression: String
): String {
    if (!type.isJavaPrimitive) {
        return expression
    }
    return when (type.name) {
        Km.BOOLEAN -> "kotlin.coroutines.jvm.internal.Boxing.boxBoolean($expression)"
        Km.BYTE -> "kotlin.coroutines.jvm.internal.Boxing.boxByte($expression)"
        Km.CHAR -> "kotlin.coroutines.jvm.internal.Boxing.boxChar($expression)"
        Km.DOUBLE -> "kotlin.coroutines.jvm.internal.Boxing.boxDouble($expression)"
        Km.FLOAT -> "kotlin.coroutines.jvm.internal.Boxing.boxFloat($expression)"
        Km.INT -> "kotlin.coroutines.jvm.internal.Boxing.boxInt($expression)"
        Km.LONG -> "kotlin.coroutines.jvm.internal.Boxing.boxLong($expression)"
        Km.SHORT -> "kotlin.coroutines.jvm.internal.Boxing.boxShort($expression)"
        else -> throw RuntimeException("Unknown primitive type ${type.name}")
    }
}

/**
 * Returns a string that casts the given expression which has type Object into the given type.
 */
fun castObjectExpression(
    type: KmType,
    expression: String
): String {
    if (!type.isJavaPrimitive) {
        return "(${type.javaTypeName})$expression"
    }

    return when (type.name) {
        Km.BOOLEAN -> "((java.lang.Boolean)$expression).booleanValue()"
        Km.BYTE -> "((java.lang.Byte)$expression).byteValue()"
        Km.CHAR -> "((java.lang.Character)$expression).charValue()"
        Km.DOUBLE -> "((java.lang.Double)$expression).doubleValue()"
        Km.FLOAT -> "((java.lang.Float)$expression).floatValue()"
        Km.INT -> "((java.lang.Integer)$expression).intValue()"
        Km.LONG -> "((java.lang.Long)$expression).longValue()"
        Km.SHORT -> "((java.lang.Short)$expression).shortValue()"
        else -> throw RuntimeException("Unknown primitive type ${type.name}")
    }
}

fun kmAliasType(
    typeName: KmName,
    alias: KmType
) = typeName.asType().also { it.abbreviatedType = alias }

fun kmListOfType(
    kmType: KmType,
    nullable: Boolean = false
): KmType =
    Km.LIST.asType().apply {
        arguments.add(KmTypeProjection(KmVariance.INVARIANT, kmType))
        isNullable = nullable
    }

/** Make sure an input type is correct and within the bounds we are currently able
 *  to handle.  null result means things are fine, otherwise a string is returned
 *  that can be used as an exception message.  */
internal fun KmType.isInputTypeFor(returnType: KmType): String? {
    if (arguments.size != returnType.arguments.size) {
        return "Argument counts don't agree (${returnType.arguments.size} != ${arguments.size}"
    }
    val thisC = classifier
    if (thisC !is KmClassifier.Class) {
        return "Currently only support Class classifiers (${thisC::class.simpleName}"
    }
    val returnC = returnType.classifier
    if (returnC !is KmClassifier.Class) {
        return "Currently only support Class classifiers (${returnType.classifier::class.simpleName}"
    }
    if (thisC.name != returnC.name) {
        return "Classifier names don't agree (${returnC.name} != ${thisC.name})."
    }

    arguments.zip(returnType.arguments).forEach { (myArg, returnArg) ->
        val thisT = myArg.type
        val returnT = returnArg.type
        if (thisT == null || returnT == null) {
            return "Argument type can't be null ($returnT, $thisT)."
        }
        thisT.isInputTypeFor(returnT)?.let {
            return ".$it" // number of leading periods tells debugger how deep we went
        }
    }

    return null
}

fun KmType.boxedJavaName(): JavaName = JavaName(kmToJvmBoxedName(this.name).replace('$', '.').replace('/', '.'))

/** render a KmType into its kotlin code representation */
val KmType.kotlinTypeString: String get() {
    val args = arguments.map {
        if (it == KmTypeProjection.STAR) {
            "*"
        } else {
            val label = when (it.variance) {
                KmVariance.OUT -> "out"
                KmVariance.IN -> "in"
                else -> null
            }
            listOf(label, it.type?.kotlinTypeString)
                .filterNotNull()
                .joinToString(" ")
        }
    }

    val type = this
    return buildString {
        append(type.name.asJavaName)
        if (args.isNotEmpty()) {
            append(args.joinToString(separator = ",", prefix = "<", postfix = ">"))
        }
        if (type.isNullable) {
            append("?")
        }
    }
}

// Property-related utilities

fun getterName(propertyName: String): String = if (startsWithIs(propertyName)) propertyName else "get${propertyName.capitalize()}"

fun setterName(propertyName: String): String {
    val setName = if (startsWithIs(propertyName)) propertyName.drop(2) else propertyName.capitalize()
    return "set$setName"
}

/**
 * There are special property getter / setter naming conventions for properties that start with "is", see:
 * https://kotlinlang.org/docs/java-to-kotlin-interop.html#properties
 */
private fun startsWithIs(s: String): Boolean {
    if (s.length < 3) return false

    // "is" followed by an upper case letter or number
    return s.startsWith("is") && !s[2].isLowerCase()
}
