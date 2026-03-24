package viaduct.codegen.km

import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties
import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmAnnotation
import kotlinx.metadata.KmAnnotationArgument
import org.junit.jupiter.api.Test
import viaduct.codegen.utils.KmName

// Annotations to be used in test
// Intent is to cover all annotation parameter types

annotation class Simple

annotation class Single(
    val i: Int
)

annotation class ScalarParams(
    val b: Boolean,
    val byte: Byte,
    val c: Char,
    val d: Double,
    val f: Float,
    val i: Int,
    val l: Long,
    val s: Short
)

enum class E {
    A { // Force creation of subclass
        override fun toString(): String = super.toString()
    },
    B,
    C
}

annotation class OtherParams(
    val a: Simple,
    val e: E,
    val k: KClass<*>,
    val s: String
)

annotation class IntArrayParam(
    val value: IntArray
)

annotation class StringArrayParam(
    vararg val params: String
)

annotation class EmptyArrayParam(
    vararg val params: String
)

// Test subject

@Simple
@Single(2)
@ScalarParams(
    true,
    1.toByte(),
    'c',
    1.0,
    1.0.toFloat(),
    1,
    1.toLong(),
    1.toShort()
)
@OtherParams(Simple(), E.A, HeavilyAnnotatedInterface::class, "hi")
@IntArrayParam(intArrayOf(1, 2))
@StringArrayParam("hi", "there")
@EmptyArrayParam()
interface HeavilyAnnotatedInterface

// Test code

/** Mostly a test of [ConstPool.asCtMemberValue] in KmToCtUtils2.kt.
 */
class ClassLoaderAnnotationTests {
    @Test
    fun testAnnotations() {
        val kmCtx = KmClassFilesBuilder()
        val simple = KmAnnotation(Simple::class.kmName.toString(), mapOf())
        val annotations =
            setOf(
                simple to true,
                KmAnnotation(
                    Single::class.kmName.toString(),
                    mapOf("i" to toKmAnnotationArgument(2))
                ) to true,
                KmAnnotation(
                    ScalarParams::class.kmName.toString(),
                    mapOf(
                        "b" to toKmAnnotationArgument(true),
                        "byte" to toKmAnnotationArgument(1.toByte()),
                        "c" to toKmAnnotationArgument('c'),
                        "d" to toKmAnnotationArgument(1.0),
                        "f" to toKmAnnotationArgument(1.0.toFloat()),
                        "i" to toKmAnnotationArgument(1),
                        "l" to toKmAnnotationArgument(1.toLong()),
                        "s" to toKmAnnotationArgument(1.toShort())
                    )
                ) to true,
                KmAnnotation(
                    OtherParams::class.kmName.toString(),
                    mapOf(
                        "a" to toKmAnnotationArgument(simple),
                        "e" to toKmAnnotationArgument(E.A),
                        "k" to toKmAnnotationArgument(HeavilyAnnotatedInterface::class),
                        "s" to toKmAnnotationArgument("hi")
                    )
                ) to true,
                KmAnnotation(
                    IntArrayParam::class.kmName.toString(),
                    mapOf("value" to toKmAnnotationArgument(listOf(1, 2)))
                ) to true,
                KmAnnotation(
                    StringArrayParam::class.kmName.toString(),
                    mapOf("params" to toKmAnnotationArgument(listOf("hi", "there")))
                ) to true,
                KmAnnotation(
                    EmptyArrayParam::class.kmName.toString(),
                    mapOf("params" to toKmAnnotationArgument(listOf<String>()))
                ) to true
            )
        kmCtx.customClassBuilder(
            ClassKind.INTERFACE,
            KmName("$actualspkg/HeavilyAnnotatedInterface"),
            annotations
        )
        kmCtx.assertNoDiff(HeavilyAnnotatedInterface::class, "$actualspkg.HeavilyAnnotatedInterface")
    }
}

// Helper functions

private fun KClass<*>.annotationOf(args: Map<String, KmAnnotationArgument>) = KmAnnotation(this.kmName.toString(), args)

private val Annotation.asKmAnnotation: KmAnnotation get() {
    val k = this::class
    val args = mutableMapOf<String, KmAnnotationArgument>()
    k.declaredMemberProperties.forEach {
        args[it.name] = toKmAnnotationArgument(it.getter.call(this))
    }
    val result = KmAnnotation(k.kmName.toString(), args)
    return result
}

private fun <T : Annotation> KClass<T>.make(vararg arg: Any): T = this.constructors.first().call(*arg)

private fun toKmAnnotationArgument(value: Any?): KmAnnotationArgument =
    when (value) {
        is List<*> -> KmAnnotationArgument.ArrayValue(value.map(::toKmAnnotationArgument))
        is Boolean -> KmAnnotationArgument.BooleanValue(value)
        is Byte -> KmAnnotationArgument.ByteValue(value)
        is Char -> KmAnnotationArgument.CharValue(value)
        is Double -> KmAnnotationArgument.DoubleValue(value)
        is Enum<*> ->
            KmAnnotationArgument.EnumValue(
                enumClassName = value::class.kmName.toString().split('.')[0], // subclasses are nested
                enumEntryName = value.toString()
            )
        is Float -> KmAnnotationArgument.FloatValue(value)
        is Int -> KmAnnotationArgument.IntValue(value)
        is Long -> KmAnnotationArgument.LongValue(value)
        is KClass<*> -> {
            // Doesn't work for KClass<Array>, KClass<IntArray>, etc.
            KmAnnotationArgument.KClassValue(value.kmName.toString())
        }
        is KmAnnotation -> KmAnnotationArgument.AnnotationValue(value)
        is Short -> KmAnnotationArgument.ShortValue(value)
        is String -> KmAnnotationArgument.StringValue(value)
        else -> throw IllegalArgumentException("Unexpected annotation argument ($value).")
    }
