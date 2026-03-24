package viaduct.codegen.km

import actualspkg.Iface
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

private const val actualPkg = "actuals"

class SyntheticBridgeTest {
    private class Fixture {
        val builders = KmClassFilesBuilder()

        val impl: Class<*>
        val read: Method?
        val readBridge: Method?
        val write: Method?
        val writeBridge: Method?

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
            val loader = builders.buildClassLoader()
            impl = loader.loadClass("$actualPkg.Impl")

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
        }
    }
}
