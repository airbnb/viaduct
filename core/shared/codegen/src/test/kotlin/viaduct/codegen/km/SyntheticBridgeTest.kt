package viaduct.codegen.km

import actualspkg.CovariantRead
import actualspkg.Iface
import actualspkg.StringRead
import java.lang.reflect.Method
import javassist.ClassPool
import javassist.bytecode.AccessFlag
import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.KmVariance
import kotlinx.metadata.Visibility
import kotlinx.metadata.visibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import viaduct.codegen.km.ctdiff.ClassDiff
import viaduct.codegen.utils.JavaName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName

class Impl : Iface<String> {
    final override fun read(): String = "a"

    final override fun write(t: String): Boolean = true
}

class CovariantImpl : CovariantRead {
    final override fun read(): String = "a"
}

private const val actualPkg = "actuals"

class SyntheticBridgeTest {
    private class Fixture {
        val builders = KmClassFilesBuilder()

        val impl: Class<*>
        val read: Method?
        val readBridge: Method?
        val write: Method?
        val writeBridge: Method?
        val covariantImpl: Class<*>
        val covariantRead: Method?
        val covariantReadBridge: Method?
        val objectReadImpl: Class<*>
        val objectRead: Method?
        val objectReadBridge: Method?

        val pool: ClassPool
            get() = builders.classPool

        init {
            builders.addBuilder(
                CustomClassBuilder(ClassKind.CLASS, KmName("$actualPkg/Impl")).apply {
                    addSupertype(
                        JavaName(Iface::class.qualifiedName!!).asKmName.asType().also {
                            it.arguments +=
                                KmTypeProjection(
                                    KmVariance.INVARIANT,
                                    Km.STRING.asType()
                                )
                        }
                    )
                    addEmptyCtor()
                    addFunction(
                        KmFunction("read").apply {
                            visibility = Visibility.PUBLIC
                            returnType = Km.STRING.asType()
                        },
                        "{ return \"a\"; }",
                        bridgeParameters = setOf(-1)
                    )
                    addFunction(
                        KmFunction("write").apply {
                            visibility = Visibility.PUBLIC
                            returnType = Km.BOOLEAN.asType()
                            valueParameters +=
                                KmValueParameter("t").apply {
                                    type = Km.STRING.asType()
                                }
                        },
                        "{ return true; }",
                        bridgeParameters = setOf(0)
                    )
                }
            )
            builders.addBuilder(
                CustomClassBuilder(ClassKind.CLASS, KmName("$actualPkg/CovariantImpl")).apply {
                    addSupertype(JavaName(CovariantRead::class.qualifiedName!!).asKmName.asType())
                    addEmptyCtor()
                    addFunction(
                        KmFunction("read").apply {
                            visibility = Visibility.PUBLIC
                            returnType = Km.STRING.asType()
                        },
                        "{ return \"a\"; }",
                        bridgeParameters = setOf(-1),
                        bridgeReturnType = JavaName("java.lang.CharSequence").asKmName.asType()
                    )
                }
            )
            builders.addBuilder(
                CustomClassBuilder(ClassKind.CLASS, KmName("$actualPkg/ObjectReadImpl")).apply {
                    addSupertype(JavaName(StringRead::class.qualifiedName!!).asKmName.asType())
                    addEmptyCtor()
                    addFunction(
                        KmFunction("read").apply {
                            visibility = Visibility.PUBLIC
                            returnType = Km.ANY.asType()
                        },
                        "{ return (Object)\"a\"; }",
                        bridgeParameters = setOf(-1),
                        bridgeReturnType = Km.STRING.asType(),
                        bridgeBody = "{ return (String)(Object)\"a\"; }"
                    )
                }
            )
            val loader = builders.buildClassLoader()
            impl = loader.loadClass("$actualPkg.Impl")
            covariantImpl = loader.loadClass("$actualPkg.CovariantImpl")
            objectReadImpl = loader.loadClass("$actualPkg.ObjectReadImpl")

            read =
                impl.declaredMethods.firstOrNull { m ->
                    m.name == "read" && !m.isSynthetic && m.returnType == String::class.java
                }
            readBridge =
                impl.declaredMethods.firstOrNull { m ->
                    m.name == "read" && hasBridgeFlags(m) && m.returnType == java.lang.Object::class.java
                }
            write =
                impl.declaredMethods.firstOrNull { m ->
                    m.name == "write" && !m.isSynthetic && m.parameterTypes.first() == String::class.java
                }
            writeBridge =
                impl.declaredMethods.firstOrNull { m ->
                    m.name == "write" && hasBridgeFlags(m) && m.parameterTypes.first() == java.lang.Object::class.java
                }
            covariantRead =
                covariantImpl.declaredMethods.firstOrNull { m ->
                    m.name == "read" && !m.isSynthetic && m.returnType == String::class.java
                }
            covariantReadBridge =
                covariantImpl.declaredMethods.firstOrNull { m ->
                    m.name == "read" && hasBridgeFlags(m) && m.returnType == CharSequence::class.java
                }
            objectRead =
                objectReadImpl.declaredMethods.firstOrNull { m ->
                    m.name == "read" && !m.isSynthetic && m.returnType == java.lang.Object::class.java
                }
            objectReadBridge =
                objectReadImpl.declaredMethods.firstOrNull { m ->
                    m.name == "read" && hasBridgeFlags(m) && m.returnType == String::class.java
                }
        }

        private fun hasBridgeFlags(m: Method): Boolean = (m.modifiers and (AccessFlag.PUBLIC or AccessFlag.SYNTHETIC or AccessFlag.BRIDGE)) != 0
    }

    @Test
    fun `generates bridge methods`() {
        Fixture().apply {
            assertNotNull(read)
            assertNotNull(readBridge)
            assertNotNull(write)
            assertNotNull(writeBridge)
            assertNotNull(covariantRead)
            assertNotNull(covariantReadBridge)
            assertNotNull(objectRead)
            assertNotNull(objectReadBridge)
        }
    }

    @Test
    fun `matches expected`() {
        Fixture().let { fixture ->
            ClassDiff(
                expectedPkg = "viaduct.codegen.km",
                actualPkg = actualPkg,
                javassistPool = fixture.pool
            ).let { diff ->
                diff.compare(Impl::class.java, fixture.impl)
                diff.compare(CovariantImpl::class.java, fixture.covariantImpl)
                diff.diffs.assertEmpty("\n")
            }
        }
    }

    @Test
    fun `methods can be invoked`() {
        Fixture().apply {
            val obj = this.impl.getConstructor().newInstance()

            assertEquals("a", read!!.invoke(obj))
            assertEquals("a", readBridge!!.invoke(obj))
            assertEquals(true, write!!.invoke(obj, "a"))
            assertEquals(true, writeBridge!!.invoke(obj, "a"))

            val covariantObj = this.covariantImpl.getConstructor().newInstance()
            assertEquals("a", covariantRead!!.invoke(covariantObj))
            assertEquals("a", covariantReadBridge!!.invoke(covariantObj))

            val objectReadObj = this.objectReadImpl.getConstructor().newInstance()
            assertEquals("a", objectRead!!.invoke(objectReadObj))
            assertEquals("a", objectReadBridge!!.invoke(objectReadObj))
        }
    }
}
