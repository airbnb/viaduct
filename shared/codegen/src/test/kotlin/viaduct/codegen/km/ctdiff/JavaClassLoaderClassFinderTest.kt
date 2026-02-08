package viaduct.codegen.km.ctdiff

import actualspkg.AgreementTests
import actualspkg.Testing
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class JavaClassLoaderClassFinderTest {
    private val classFinder = JavaClassLoaderClassFinder.fromSystemClassLoader()

    @Nested
    inner class FindTests {
        @Test
        fun `find loads class by fully qualified name`() {
            val cls = classFinder.find("java.lang.String")
            cls shouldBe String::class.java
        }

        @Test
        fun `find loads nested class`() {
            val cls = classFinder.find("actualspkg.AgreementTests\$EmptyClass")
            cls shouldBe AgreementTests.EmptyClass::class.java
        }

        @Test
        fun `find throws ClassNotFoundException for non-existent class`() {
            shouldThrow<ClassNotFoundException> {
                classFinder.find("com.nonexistent.FakeClass")
            }
        }

        @Test
        fun `find loads interface`() {
            val cls = classFinder.find("java.lang.Runnable")
            cls shouldBe Runnable::class.java
        }

        @Test
        fun `find loads enum`() {
            val cls = classFinder.find("java.lang.Thread\$State")
            cls shouldBe Thread.State::class.java
        }

        @Test
        fun `find loads annotation class`() {
            val cls = classFinder.find("actualspkg.Testing")
            cls shouldBe Testing::class.java
        }
    }

    @Nested
    inner class GetClassAnnotationsTests {
        @Test
        fun `getClassAnnotations returns empty list for unannotated class`() {
            val cls = AgreementTests.EmptyClass::class.java
            val annotations = classFinder.getClassAnnotations(cls)
            annotations.shouldBeEmpty()
        }

        @Test
        fun `getClassAnnotations returns annotations for annotated class`() {
            val cls = AgreementTests.WithFields::class.java
            val annotations = classFinder.getClassAnnotations(cls)
            annotations shouldHaveSize 1
            annotations[0] shouldContain "Testing"
            annotations[0] shouldStartWith "RuntimeVisibleAnnotations:"
        }

        @Test
        fun `getClassAnnotations excludes kotlin Metadata annotation`() {
            // Kotlin classes have Metadata annotation which should be filtered out
            val cls = JavaClassLoaderClassFinderTest::class.java
            val annotations = classFinder.getClassAnnotations(cls)
            annotations.none { it.contains("kotlin.Metadata") } shouldBe true
        }

        @Test
        fun `getClassAnnotations returns multiple annotations`() {
            val cls = MultiAnnotatedClass::class.java
            val annotations = classFinder.getClassAnnotations(cls)
            // Deprecated is runtime visible, Suppress is not (SOURCE retention)
            annotations.size shouldBe 1
            annotations.any { it.contains("Deprecated") } shouldBe true
        }
    }

    @Nested
    inner class GetFieldAnnotationsTests {
        @Test
        fun `getFieldAnnotations returns empty list for unannotated field`() {
            val cls = AgreementTests.WithFields::class.java
            val annotations = classFinder.getFieldAnnotations(cls, "f1")
            annotations.shouldBeEmpty()
        }

        @Test
        fun `getFieldAnnotations returns annotations for annotated field`() {
            val cls = AgreementTests.WithFields::class.java
            val annotations = classFinder.getFieldAnnotations(cls, "f2")
            annotations shouldHaveSize 1
            annotations[0] shouldContain "Testing"
            annotations[0] shouldStartWith "RuntimeVisibleAnnotations:"
        }

        @Test
        fun `getFieldAnnotations throws for non-existent field`() {
            val cls = AgreementTests.WithFields::class.java
            shouldThrow<NoSuchFieldException> {
                classFinder.getFieldAnnotations(cls, "nonExistentField")
            }
        }

        @Test
        fun `getFieldAnnotations works for private field`() {
            val cls = AgreementTests.WithFields::class.java
            val annotations = classFinder.getFieldAnnotations(cls, "f5")
            annotations.shouldBeEmpty()
        }
    }

    @Nested
    inner class GetMethodSignaturesTests {
        @Test
        fun `getMethodSignatures returns method info for all declared methods`() {
            val cls = MethodTestClass::class.java
            val methods = classFinder.getMethodSignatures(cls)

            // Should not be empty
            methods.size shouldBe 4 // noArgs, withPrimitiveArgs, withObjectArgs, returnsObject

            val signatures = methods.map { it.signature }
            signatures shouldContain "noArgs ()V"
            signatures shouldContain "withPrimitiveArgs (I,J,Z)V"
            signatures shouldContain "withObjectArgs (Ljava/lang/String;,Ljava/util/List;)V"
            signatures shouldContain "returnsObject ()Ljava/lang/String;"
        }

        @Test
        fun `getMethodSignatures excludes jacoco methods`() {
            val cls = MethodTestClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            methods.none { it.signature.startsWith("\$jacoco") } shouldBe true
        }

        @Test
        fun `getMethodSignatures returns correct modifiers`() {
            val cls = MethodTestClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val publicMethod = methods.find { it.signature.startsWith("noArgs") }!!
            Modifier.isPublic(publicMethod.modifiers) shouldBe true
        }

        @Test
        fun `getMethodSignatures returns annotations for annotated methods`() {
            val cls = MethodTestClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val annotatedMethod = methods.find { it.signature.startsWith("withPrimitiveArgs") }!!
            annotatedMethod.annotations shouldHaveSize 1
            annotatedMethod.annotations[0] shouldContain "Deprecated"
        }

        @Test
        fun `getMethodSignatures handles arrays correctly`() {
            val cls = ArrayMethodClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val arrayMethod = methods.find { it.signature.startsWith("arrayMethod") }!!
            arrayMethod.signature shouldContain "[Ljava/lang/String;"
        }

        @Test
        fun `getMethodSignatures handles primitive arrays`() {
            val cls = ArrayMethodClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val primitiveArrayMethod = methods.find { it.signature.startsWith("primitiveArrayMethod") }!!
            primitiveArrayMethod.signature shouldContain "[I"
        }

        @Test
        fun `getMethodSignatures handles 2D arrays`() {
            val cls = ArrayMethodClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val twoDArrayMethod = methods.find { it.signature.startsWith("twoDArrayMethod") }!!
            twoDArrayMethod.signature shouldContain "[[I"
        }
    }

    @Nested
    inner class GetConstructorSignaturesTests {
        @Test
        fun `getConstructorSignatures returns constructor info`() {
            val cls = AgreementTests.WithCtors::class.java
            val ctors = classFinder.getConstructorSignatures(cls)

            // WithCtors has 3 constructors + synthetic outer class reference
            ctors.size shouldBe 3

            val signatures = ctors.map { it.signature }
            // Note: inner class constructors have outer class as first parameter
            signatures.any { it.contains("(Lactualspkg/AgreementTests;)V") } shouldBe true
            signatures.any { it.contains("I") } shouldBe true // int param
        }

        @Test
        fun `getConstructorSignatures returns correct modifiers`() {
            val cls = ConstructorTestClass::class.java
            val ctors = classFinder.getConstructorSignatures(cls)

            val publicCtor = ctors.find { it.signature == "<init> ()V" }!!
            Modifier.isPublic(publicCtor.modifiers) shouldBe true
        }

        @Test
        fun `getConstructorSignatures returns annotations for annotated constructors`() {
            val cls = AgreementTests.WithCtors::class.java
            val ctors = classFinder.getConstructorSignatures(cls)
            val annotatedCtor = ctors.find { it.annotations.isNotEmpty() }
            annotatedCtor shouldBe ctors.find { it.signature.contains("WithCtors;)V") }
        }

        @Test
        fun `getConstructorSignatures handles default constructor`() {
            val cls = AgreementTests.EmptyClass::class.java
            val ctors = classFinder.getConstructorSignatures(cls)
            ctors.size shouldBe 1
            // Inner class has outer class reference
            ctors[0].signature shouldContain "<init>"
        }
    }

    @Nested
    inner class TypeDescriptorTests {
        @Test
        fun `type descriptors for all primitive types`() {
            val cls = PrimitiveTypesClass::class.java
            val methods = classFinder.getMethodSignatures(cls)

            // Test void return
            methods.find { it.signature.startsWith("voidMethod") }!!.signature shouldContain "()V"

            // Test boolean
            methods.find { it.signature.startsWith("booleanMethod") }!!.signature shouldContain "(Z)"

            // Test byte
            methods.find { it.signature.startsWith("byteMethod") }!!.signature shouldContain "(B)"

            // Test char
            methods.find { it.signature.startsWith("charMethod") }!!.signature shouldContain "(C)"

            // Test short
            methods.find { it.signature.startsWith("shortMethod") }!!.signature shouldContain "(S)"

            // Test int
            methods.find { it.signature.startsWith("intMethod") }!!.signature shouldContain "(I)"

            // Test long
            methods.find { it.signature.startsWith("longMethod") }!!.signature shouldContain "(J)"

            // Test float
            methods.find { it.signature.startsWith("floatMethod") }!!.signature shouldContain "(F)"

            // Test double
            methods.find { it.signature.startsWith("doubleMethod") }!!.signature shouldContain "(D)"
        }

        @Test
        fun `type descriptor for primitive return types`() {
            val cls = PrimitiveReturnClass::class.java
            val methods = classFinder.getMethodSignatures(cls)

            methods.find { it.signature.startsWith("returnBoolean") }!!.signature shouldBe "returnBoolean ()Z"
            methods.find { it.signature.startsWith("returnByte") }!!.signature shouldBe "returnByte ()B"
            methods.find { it.signature.startsWith("returnChar") }!!.signature shouldBe "returnChar ()C"
            methods.find { it.signature.startsWith("returnShort") }!!.signature shouldBe "returnShort ()S"
            methods.find { it.signature.startsWith("returnInt") }!!.signature shouldBe "returnInt ()I"
            methods.find { it.signature.startsWith("returnLong") }!!.signature shouldBe "returnLong ()J"
            methods.find { it.signature.startsWith("returnFloat") }!!.signature shouldBe "returnFloat ()F"
            methods.find { it.signature.startsWith("returnDouble") }!!.signature shouldBe "returnDouble ()D"
        }
    }

    @Nested
    inner class FactoryMethodTests {
        @Test
        fun `fromSystemClassLoader creates working finder`() {
            val finder = JavaClassLoaderClassFinder.fromSystemClassLoader()
            val cls = finder.find("java.lang.String")
            cls shouldBe String::class.java
        }

        @Test
        fun `custom ClassLoader is used for class loading`() {
            val customLoader = object : ClassLoader() {
                override fun loadClass(name: String): Class<*> {
                    if (name == "custom.TestClass") {
                        throw ClassNotFoundException("Custom loader: $name")
                    }
                    return super.loadClass(name)
                }
            }
            val finder = JavaClassLoaderClassFinder(customLoader)
            shouldThrow<ClassNotFoundException> {
                finder.find("custom.TestClass")
            }.message shouldContain "Custom loader"
        }
    }

    @Nested
    inner class EnumTests {
        @Test
        fun `getMethodSignatures handles enum methods`() {
            val cls = Thread.State::class.java
            val methods = classFinder.getMethodSignatures(cls)

            // Enums have values() and valueOf() methods
            val signatures = methods.map { it.signature }
            signatures.any { it.startsWith("values") } shouldBe true
            signatures.any { it.startsWith("valueOf") } shouldBe true
        }

        @Test
        fun `getConstructorSignatures handles enum constructors`() {
            val cls = TestEnum::class.java
            val ctors = classFinder.getConstructorSignatures(cls)
            // Enum constructors have (String, int) signature
            ctors.isNotEmpty() shouldBe true
            ctors[0].signature shouldContain "<init>"
        }
    }

    @Nested
    inner class InterfaceTests {
        @Test
        fun `getMethodSignatures returns interface methods`() {
            val cls = TestInterface::class.java
            val methods = classFinder.getMethodSignatures(cls)

            methods.size shouldBe 1
            methods[0].signature shouldBe "interfaceMethod (Ljava/lang/String;)Ljava/lang/String;"
        }

        @Test
        fun `getConstructorSignatures returns empty for interface`() {
            val cls = TestInterface::class.java
            val ctors = classFinder.getConstructorSignatures(cls)
            ctors.shouldBeEmpty()
        }
    }

    @Nested
    inner class EdgeCaseTests {
        @Test
        fun `handles class with no methods`() {
            val cls = EmptyTestClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            methods.shouldBeEmpty()
        }

        @Test
        fun `handles class with only static methods`() {
            val cls = StaticMethodsClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            methods shouldHaveSize 1
            Modifier.isStatic(methods[0].modifiers) shouldBe true
        }

        @Test
        fun `handles generic method parameters`() {
            val cls = GenericMethodClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val genericMethod = methods.find { it.signature.startsWith("genericMethod") }!!
            genericMethod.signature shouldContain "Ljava/util/List;"
        }

        @Test
        fun `handles abstract class`() {
            val cls = AbstractTestClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val abstractMethod = methods.find { it.signature.startsWith("abstractMethod") }!!
            Modifier.isAbstract(abstractMethod.modifiers) shouldBe true
        }

        @Test
        fun `handles protected methods`() {
            val cls = VisibilityTestClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val protectedMethod = methods.find { it.signature.startsWith("protectedMethod") }!!
            Modifier.isProtected(protectedMethod.modifiers) shouldBe true
        }

        @Test
        fun `handles private methods`() {
            val cls = VisibilityTestClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val privateMethod = methods.find { it.signature.startsWith("privateMethod") }!!
            Modifier.isPrivate(privateMethod.modifiers) shouldBe true
        }

        @Test
        fun `handles final methods`() {
            val cls = VisibilityTestClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val finalMethod = methods.find { it.signature.startsWith("finalMethod") }!!
            Modifier.isFinal(finalMethod.modifiers) shouldBe true
        }

        @Test
        fun `handles object array return type`() {
            val cls = ArrayMethodClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val arrayReturnMethod = methods.find { it.signature.startsWith("returnObjectArray") }!!
            arrayReturnMethod.signature shouldBe "returnObjectArray ()[Ljava/lang/Object;"
        }

        @Test
        fun `handles 3D array parameter`() {
            val cls = ArrayMethodClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val threeDArrayMethod = methods.find { it.signature.startsWith("threeDArrayMethod") }!!
            threeDArrayMethod.signature shouldContain "[[[D"
        }

        @Test
        fun `handles static fields`() {
            val cls = FieldTestClass::class.java
            val annotations = classFinder.getFieldAnnotations(cls, "staticField")
            annotations.shouldBeEmpty()
        }

        @Test
        fun `handles synchronized methods`() {
            val cls = SynchronizedTestClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val syncMethod = methods.find { it.signature.startsWith("synchronizedMethod") }!!
            Modifier.isSynchronized(syncMethod.modifiers) shouldBe true
        }

        @Test
        fun `handles native methods`() {
            // The system class has native methods
            val cls = Thread::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val nativeMethods = methods.filter { Modifier.isNative(it.modifiers) }
            nativeMethods.isNotEmpty() shouldBe true
        }

        @Test
        fun `handles varargs method`() {
            val cls = VarargsTestClass::class.java
            val methods = classFinder.getMethodSignatures(cls)
            val varargsMethod = methods.find { it.signature.startsWith("varargsMethod") }!!
            // Varargs are represented as arrays
            varargsMethod.signature shouldContain "[Ljava/lang/String;"
        }
    }

    @Nested
    inner class MultipleConstructorsTests {
        @Test
        fun `handles multiple constructors with different visibility`() {
            val cls = MultiConstructorClass::class.java
            val ctors = classFinder.getConstructorSignatures(cls)

            ctors.size shouldBe 3

            val publicCtor = ctors.find { it.signature == "<init> ()V" }!!
            Modifier.isPublic(publicCtor.modifiers) shouldBe true

            val privateCtor = ctors.find { it.signature == "<init> (I)V" }!!
            Modifier.isPrivate(privateCtor.modifiers) shouldBe true

            val protectedCtor = ctors.find { it.signature == "<init> (Ljava/lang/String;)V" }!!
            Modifier.isProtected(protectedCtor.modifiers) shouldBe true
        }
    }

    // Test fixture classes
    @Deprecated("For testing")
    @Suppress("unused")
    class MultiAnnotatedClass

    @Suppress("unused", "UNUSED_PARAMETER")
    class MethodTestClass {
        fun noArgs() = Unit

        @Deprecated("test")
        fun withPrimitiveArgs(
            i: Int,
            l: Long,
            b: Boolean
        ) = Unit

        fun withObjectArgs(
            s: String,
            list: List<String>
        ) = Unit

        fun returnsObject(): String = ""
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    class ArrayMethodClass {
        fun arrayMethod(arr: Array<String>) = Unit

        fun primitiveArrayMethod(arr: IntArray) = Unit

        fun twoDArrayMethod(arr: Array<IntArray>) = Unit

        fun returnObjectArray(): Array<Any> = emptyArray()

        fun threeDArrayMethod(arr: Array<Array<DoubleArray>>) = Unit
    }

    @Suppress("unused")
    class ConstructorTestClass {
        constructor()
        private constructor(x: Int)
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    class PrimitiveTypesClass {
        fun voidMethod() = Unit

        fun booleanMethod(b: Boolean) = Unit

        fun byteMethod(b: Byte) = Unit

        fun charMethod(c: Char) = Unit

        fun shortMethod(s: Short) = Unit

        fun intMethod(i: Int) = Unit

        fun longMethod(l: Long) = Unit

        fun floatMethod(f: Float) = Unit

        fun doubleMethod(d: Double) = Unit
    }

    @Suppress("unused")
    class PrimitiveReturnClass {
        fun returnBoolean(): Boolean = false

        fun returnByte(): Byte = 0

        fun returnChar(): Char = 'a'

        fun returnShort(): Short = 0

        fun returnInt(): Int = 0

        fun returnLong(): Long = 0L

        fun returnFloat(): Float = 0f

        fun returnDouble(): Double = 0.0
    }

    enum class TestEnum { VALUE_A, VALUE_B }

    interface TestInterface {
        fun interfaceMethod(s: String): String
    }

    class EmptyTestClass

    @Suppress("unused")
    object StaticMethodsClass {
        @JvmStatic
        fun staticMethod() = Unit
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    class GenericMethodClass {
        fun genericMethod(list: List<String>): List<Int> = emptyList()
    }

    @Suppress("unused")
    abstract class AbstractTestClass {
        abstract fun abstractMethod(): String

        fun concreteMethod(): Int = 42
    }

    @Suppress("unused")
    open class VisibilityTestClass {
        protected fun protectedMethod() = Unit

        private fun privateMethod() = Unit

        internal fun internalMethod() = Unit

        final fun finalMethod() = Unit
    }

    @Suppress("unused")
    class FieldTestClass {
        companion object {
            @JvmStatic
            val staticField: Int = 0
        }
    }

    @Suppress("unused")
    class SynchronizedTestClass {
        @Synchronized
        fun synchronizedMethod() = Unit
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    class VarargsTestClass {
        fun varargsMethod(vararg args: String) = Unit
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    class MultiConstructorClass {
        constructor()
        private constructor(x: Int)
        protected constructor(s: String)
    }
}
