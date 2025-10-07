@file:Suppress("ForbiddenImport")

package viaduct.codegen.km

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.Visibility
import kotlinx.metadata.isSuspend
import kotlinx.metadata.visibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.utils.JavaBinaryName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName

interface ClBasicInterface {
    fun fn(a: String): String
}

class ClassLoaderTests {
    @Test
    fun basicGoodInterfaceTest() {
        val kmCtx = KmClassFilesBuilder()
        kmCtx.customClassBuilder(ClassKind.CLASS, KmName("ActualBasicInterface")).apply {
            addSupertype(ClBasicInterface::class.kmType)
            addFun(
                "fn",
                Km.STRING.asType(),
                listOf(
                    KmValueParameter("a").apply {
                        type = Km.STRING.asType()
                    }
                ),
                "{ return $1; }"
            )
        }
        val c = kmCtx.loadClass(JavaBinaryName("ActualBasicInterface"))
        val i = c.getDeclaredConstructor().newInstance() as ClBasicInterface
        assertEquals(i.fn("foo"), "foo")
    }

    abstract class BasicClass {
        abstract fun fn(a: String): String
    }

    @Test
    fun basicGoodClassTest() {
        val kmCtx = KmClassFilesBuilder()
        kmCtx.customClassBuilder(ClassKind.CLASS, KmName("ActualBasicClass")).apply {
            addSupertype(BasicClass::class.kmType)
            addFun(
                "fn",
                Km.STRING.asType(),
                listOf(
                    KmValueParameter("a").apply {
                        type = Km.STRING.asType()
                    }
                ),
                "{ return $1; }"
            )
        }
        val c = kmCtx.loadClass(JavaBinaryName("ActualBasicClass"))
        val i = c.getDeclaredConstructor().newInstance() as BasicClass
        assertEquals(i.fn("foo"), "foo")
    }

    @Test
    fun missingMethod() {
        val kmCtx = KmClassFilesBuilder()
        kmCtx.customClassBuilder(ClassKind.CLASS, KmName("MissingMethod")).apply {
            addSupertype(ClBasicInterface::class.kmType)
        }
        val c = kmCtx.loadClass(JavaBinaryName("MissingMethod"))
        val i = c.getDeclaredConstructor().newInstance() as ClBasicInterface
        assertThrows(AbstractMethodError::class.java) {
            i.fn("foo")
        }
    }

    @Test
    fun wrongReturnType() {
        val kmCtx = KmClassFilesBuilder()
        kmCtx.customClassBuilder(ClassKind.CLASS, KmName("WrongReturnType")).apply {
            addSupertype(ClBasicInterface::class.kmType)
            addFun(
                "fn",
                Km.INT.asType(),
                listOf(
                    KmValueParameter("a").apply {
                        type = Km.STRING.asType()
                    }
                ),
                "{ return $1; }"
            )
        }
        val c = kmCtx.loadClass(JavaBinaryName("WrongReturnType"))
        val err =
            assertThrows(VerifyError::class.java) {
                val i = c.getDeclaredConstructor().newInstance() as ClBasicInterface
                i.fn("foo")
            }
        assertTrue(err.message!!.startsWith("Bad return type"))
    }

    @Test
    fun wrongArgumentType() {
        val kmCtx = KmClassFilesBuilder()
        kmCtx.customClassBuilder(ClassKind.CLASS, KmName("WrongArgumentType")).apply {
            addSupertype(ClBasicInterface::class.kmType)
            addFun(
                "fn",
                Km.STRING.asType(),
                listOf(
                    KmValueParameter("a").apply {
                        type = Km.INT.asType()
                    }
                ),
                "{ return null; }"
            )
        }
        val c = kmCtx.loadClass(JavaBinaryName("WrongArgumentType"))
        assertThrows(AbstractMethodError::class.java) {
            val i = c.getDeclaredConstructor().newInstance() as ClBasicInterface
            i.fn("foo")
        }
    }

    interface InterfaceWithSuspendFun {
        suspend fun fn(a: List<String>): List<String>
    }

    @Test
    fun suspendFunTest() {
        val kmCtx = KmClassFilesBuilder()
        val retType = kmListOfType(Km.STRING.asType())
        kmCtx.customClassBuilder(ClassKind.CLASS, KmName("SuspendFunTest")).apply {
            addSupertype(InterfaceWithSuspendFun::class.kmType)
            addSuspendFunction(
                KmFunction("fn").apply {
                    visibility = Visibility.PUBLIC
                    isSuspend = true
                    returnType = retType
                    valueParameters.add(
                        KmValueParameter("a").apply {
                            type = kmListOfType(Km.STRING.asType())
                        }
                    )
                },
                returnTypeAsInputForSuspend = retType,
                body = "{ return $1; }"
            )
        }
        val c = kmCtx.loadClass(JavaBinaryName("SuspendFunTest"))
        val i = c.getDeclaredConstructor().newInstance() as InterfaceWithSuspendFun
        runBlocking {
            i.fn(listOf("foo"))
        } shouldBe listOf("foo")
    }

    interface ClInterfaceWithDefaultImpls {
        fun hello(a: String) = "hello"
    }

    @Test
    fun defaultImplsTest() {
        val kmCtx = KmClassFilesBuilder()
        kmCtx.customClassBuilder(ClassKind.INTERFACE, KmName("InterfaceWithDefaultMethods")).apply {
            addSupertype(ClInterfaceWithDefaultImpls::class.kmType)
            addFun(
                "world",
                Km.STRING.asType(),
                body = "{ return \"world\"; }"
            )
        }
        val c = kmCtx.loadClass(JavaBinaryName("InterfaceWithDefaultMethods"))
        c.declaredClasses shouldHaveSize 1
        val defaultImpls = c.declaredClasses[0]
        defaultImpls.name shouldBe "InterfaceWithDefaultMethods\$DefaultImpls"
        val declaredMethods = defaultImpls.declaredMethods.filter { !it.isSynthetic }
        declaredMethods.map { it.name } shouldContainExactlyInAnyOrder listOf("hello", "world")
    }
}
