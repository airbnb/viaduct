package viaduct.codegen.km

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmClassifier
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.KmVariance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.codegen.utils.JavaBinaryName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName

class KmUtilsTest {
    @Test
    fun `Given an KmType that can't be the input type for another KmType then isInputTypeFor should return non-null`() {
        val kmInt = Km.INT.asType()
        val kmString = Km.STRING.asType()
        val kmListInt = kmListOfType(kmInt)
        val kmListString = kmListOfType(kmString)
        val kmListStar =
            Km.LIST.asType().apply {
                arguments.add(KmTypeProjection.STAR)
            }

        val kmIntAlias = KmType().apply { classifier = KmClassifier.TypeAlias("kotlin/Int") }
        val kmParam = KmType().apply { classifier = KmClassifier.TypeParameter(0) }
        kmInt.isInputTypeFor(kmListInt) shouldContain ("Argument counts")
        kmIntAlias.isInputTypeFor(kmInt) shouldContain "Class"
        kmInt.isInputTypeFor(kmParam) shouldContain "Class"
        kmInt.isInputTypeFor(kmString) shouldContain "names don't agree"
        kmListStar.isInputTypeFor(kmListInt) shouldContain "can't be null"
        kmListInt.isInputTypeFor(kmListStar) shouldContain "can't be null"
        kmListInt.isInputTypeFor(kmListString) shouldStartWith ".Classifier"
        kmListOfType(kmListInt).isInputTypeFor(kmListOfType(kmListString)) shouldStartWith "..Classifier"
    }

    @Test
    fun `isInputTypeFor -- multiple type arguments`() {
        fun mkMapType(
            k: KmType,
            v: KmType
        ) = Km.MAP.asType().apply {
            arguments.add(KmTypeProjection(KmVariance.INVARIANT, k))
            arguments.add(KmTypeProjection(KmVariance.INVARIANT, v))
        }

        val mapIntInt = mkMapType(Km.INT.asType(), Km.INT.asType())
        val mapStrInt = mkMapType(Km.STRING.asType(), Km.INT.asType())

        mapIntInt.isInputTypeFor(mapIntInt).shouldBeNull()

        mapStrInt.isInputTypeFor(mapIntInt) shouldContain ".Classifier"
        Km.INT.asType().isInputTypeFor(mapIntInt) shouldContain "Argument counts"
    }

    @Test
    fun testGetterName() {
        // starts with is
        assertEquals("isDone", getterName("isDone"))
        assertEquals("is360Degrees", getterName("is360Degrees"))

        // does not start with is
        assertEquals("getIsdone", getterName("isdone"))
        assertEquals("getIssue", getterName("issue"))
    }

    @Test
    fun testSetterName() {
        // starts with is
        assertEquals("setDone", setterName("isDone"))
        assertEquals("set360Degrees", setterName("is360Degrees"))

        // does not start with is
        assertEquals("setIsdone", setterName("isdone"))
        assertEquals("setIssue", setterName("issue"))
    }

    interface BoxingExpressionTestInterface {
        fun boxBoolean(b: Boolean): Any

        fun boxByte(b: Byte): Any

        fun boxChar(c: Char): Any

        fun boxDouble(d: Double): Any

        fun boxFloat(f: Float): Any

        fun boxInt(i: Int): Any

        fun boxLong(l: Long): Any

        fun boxShort(s: Short): Any

        fun alreadyObject(s: String): Any
    }

    @Test
    fun testBoxingExpression() {
        val kmCtx = KmClassFilesBuilder()
        kmCtx.customClassBuilder(ClassKind.CLASS, KmName("BoxingExpressionTest")).apply {
            addSupertype(BoxingExpressionTestInterface::class.kmType)
            addBoxFun("boxBoolean", Km.BOOLEAN.asType())
            addBoxFun("boxByte", Km.BYTE.asType())
            addBoxFun("boxChar", Km.CHAR.asType())
            addBoxFun("boxDouble", Km.DOUBLE.asType())
            addBoxFun("boxFloat", Km.FLOAT.asType())
            addBoxFun("boxInt", Km.INT.asType())
            addBoxFun("boxLong", Km.LONG.asType())
            addBoxFun("boxShort", Km.SHORT.asType())
            addBoxFun("alreadyObject", Km.STRING.asType())
        }
        // Boxing to an object type is primarily tested via class loading (no boxing results in a VerifyError)
        val c = kmCtx.loadClass(JavaBinaryName("BoxingExpressionTest"))
        val i = c.getDeclaredConstructor().newInstance() as BoxingExpressionTestInterface
        assert(i.boxBoolean(true) as Boolean)
        assertEquals(i.boxByte(8) as Byte, 8)
        assertEquals(i.boxChar('c') as Char, 'c')
        assertEquals(i.boxDouble(5.1) as Double, 5.1)
        assertEquals(i.boxFloat(5.9f) as Float, 5.9f)
        assertEquals(i.boxInt(2) as Int, 2)
        assertEquals(i.boxLong(3) as Long, 3L)
        assertEquals(i.boxShort(4) as Short, 4)
        assertEquals(i.alreadyObject("tralala"), "tralala")
    }

    private fun CustomClassBuilder.addBoxFun(
        name: String,
        paramType: KmType
    ) {
        addFun(
            name,
            Km.ANY.asType(),
            listOf(
                KmValueParameter("a").apply {
                    type = paramType
                }
            ),
            "{ return ${boxingExpression(paramType, "$1")}; }"
        )
    }

    interface CastObjectExpressionTestInterface {
        fun castBoolean(b: Any): Boolean

        fun castByte(b: Any): Byte

        fun castChar(c: Any): Char

        fun castDouble(d: Any): Double

        fun castFloat(f: Any): Float

        fun castInt(i: Any): Int

        fun castLong(l: Any): Long

        fun castShort(s: Any): Short

        fun castNonPrimitive(s: Any): String
    }

    @Test
    fun testCastObjectExpression() {
        val kmCtx = KmClassFilesBuilder()
        kmCtx.customClassBuilder(ClassKind.CLASS, KmName("CastObjectExpressionTest")).apply {
            addSupertype(CastObjectExpressionTestInterface::class.kmType)
            addCastFun("castBoolean", Km.BOOLEAN.asType())
            addCastFun("castByte", Km.BYTE.asType())
            addCastFun("castChar", Km.CHAR.asType())
            addCastFun("castDouble", Km.DOUBLE.asType())
            addCastFun("castFloat", Km.FLOAT.asType())
            addCastFun("castInt", Km.INT.asType())
            addCastFun("castLong", Km.LONG.asType())
            addCastFun("castShort", Km.SHORT.asType())
            addCastFun("castNonPrimitive", Km.STRING.asType())
        }
        // Casting to a primitive type is primarily tested via class loading (no casting results in a VerifyError)
        val c = kmCtx.loadClass(JavaBinaryName("CastObjectExpressionTest"))
        val i = c.getDeclaredConstructor().newInstance() as CastObjectExpressionTestInterface

        assert(i.castBoolean(true))
        assertEquals(i.castByte(8.toByte()), 8)
        assertEquals(i.castChar('c'), 'c')
        assertEquals(i.castDouble(5.1), 5.1)
        assertEquals(i.castFloat(5.9f), 5.9f)
        assertEquals(i.castInt(2), 2)
        assertEquals(i.castLong(3L), 3)
        assertEquals(i.castShort(4.toShort()), 4)
        assertEquals(i.castNonPrimitive("tralala"), "tralala")
    }

    private fun CustomClassBuilder.addCastFun(
        name: String,
        returnType: KmType
    ) {
        addFun(
            name,
            returnType,
            listOf(
                KmValueParameter("a").apply {
                    type = Km.ANY.asType()
                }
            ),
            "{ return ${castObjectExpression(returnType, "$1")}; }"
        )
    }

    @Test
    fun testKmToJvmBoxedJavaName() {
        assertEquals(
            "java.lang.Object",
            Km.ANY
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "java.lang.Boolean",
            Km.BOOLEAN
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "java.lang.Byte",
            Km.BYTE
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "java.lang.Character",
            Km.CHAR
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "java.lang.Double",
            Km.DOUBLE
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "java.lang.Float",
            Km.FLOAT
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "java.lang.Integer",
            Km.INT
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "java.util.List",
            Km.LIST
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "java.lang.Long",
            Km.LONG
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "java.util.Map",
            Km.MAP
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "java.util.Map",
            Km.MUTABLE_MAP
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "java.lang.Short",
            Km.SHORT
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "java.lang.String",
            Km.STRING
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "kotlin.Unit",
            Km.UNIT
                .asType()
                .boxedJavaName()
                .toString()
        )
        assertEquals(
            "viaduct.api.grts.TestObject.NestedObject",
            KmName("viaduct/api/grts/TestObject.NestedObject").asType().boxedJavaName().toString()
        )
        assertThrows(IllegalArgumentException::class.java) {
            Km.ARRAY.asType().boxedJavaName()
        }
    }

    @Test
    fun testKotlinTypeString() {
        assertEquals("kotlin.Int", Km.INT.asType().kotlinTypeString)
        assertEquals("kotlin.Int?", Km.INT.asNullableType().kotlinTypeString)

        // no type arguments
        assertEquals("kotlin.collections.List", Km.LIST.asType().kotlinTypeString)

        // single type arguments and variance
        Km.LIST.asType().apply {
            arguments += KmTypeProjection.STAR
            assertEquals("kotlin.collections.List<*>", kotlinTypeString)

            arguments.set(0, KmTypeProjection(KmVariance.INVARIANT, Km.INT.asType()))
            assertEquals("kotlin.collections.List<kotlin.Int>", kotlinTypeString)

            arguments.set(0, KmTypeProjection(KmVariance.INVARIANT, Km.INT.asNullableType()))
            assertEquals("kotlin.collections.List<kotlin.Int?>", kotlinTypeString)

            arguments.set(0, KmTypeProjection(KmVariance.IN, Km.INT.asType()))
            assertEquals("kotlin.collections.List<in kotlin.Int>", kotlinTypeString)

            arguments.set(0, KmTypeProjection(KmVariance.IN, Km.INT.asNullableType()))
            assertEquals("kotlin.collections.List<in kotlin.Int?>", kotlinTypeString)

            arguments.set(0, KmTypeProjection(KmVariance.OUT, Km.INT.asType()))
            assertEquals("kotlin.collections.List<out kotlin.Int>", kotlinTypeString)

            arguments.set(0, KmTypeProjection(KmVariance.OUT, Km.INT.asNullableType()))
            assertEquals("kotlin.collections.List<out kotlin.Int?>", kotlinTypeString)
        }

        // multiple type arguments and variance
        Km.MAP.asType().apply {
            arguments += KmTypeProjection.STAR
            arguments += KmTypeProjection.STAR
            assertEquals("kotlin.collections.Map<*,*>", kotlinTypeString)

            arguments.set(0, KmTypeProjection(KmVariance.INVARIANT, Km.INT.asType()))
            arguments.set(1, KmTypeProjection(KmVariance.INVARIANT, Km.INT.asType()))
            assertEquals("kotlin.collections.Map<kotlin.Int,kotlin.Int>", kotlinTypeString)

            arguments.set(0, KmTypeProjection(KmVariance.IN, Km.INT.asType()))
            arguments.set(1, KmTypeProjection(KmVariance.OUT, Km.INT.asType()))
            assertEquals("kotlin.collections.Map<in kotlin.Int,out kotlin.Int>", kotlinTypeString)
        }
    }
}
