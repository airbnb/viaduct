package viaduct.codegen.km

import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.property.shouldBeImmutable
import io.kotest.matchers.reflection.shouldHaveFunction
import io.kotest.matchers.reflection.shouldHaveMemberProperty
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaType
import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmAnnotation
import kotlinx.metadata.KmAnnotationArgument
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.Visibility
import kotlinx.metadata.visibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.KmName
import viaduct.codegen.utils.name

private const val actualPkg = "actual"
private const val defaultClassName = "Subject"

class ObjectTest {
    private class Fixture(
        fn: Fixture.() -> Unit
    ) {
        val builders = KmClassFilesBuilder()

        init {
            fn(this)
        }

        fun mkKmName(simpleName: String): KmName = KmName("$actualPkg/$simpleName")

        fun addBuilder(
            kmKind: ClassKind = ClassKind.OBJECT,
            kmName: KmName = mkKmName(defaultClassName),
            annotations: Set<Pair<KmAnnotation, Boolean>> = emptySet(),
            tier: Int = 1
        ): CustomClassBuilder = builders.customClassBuilder(kmKind = kmKind, kmName = kmName, annotations = annotations, tier = tier)

        fun loadKClass(kmName: KmName = mkKmName(defaultClassName)): KClass<*> = builders.buildClassLoader().loadClass(kmName.asJavaBinaryName.toString()).kotlin
    }

    @Test
    fun `throws on constructor`() {
        Fixture {
            addBuilder().also {
                val ctor = KmConstructor().also { it.visibility = Visibility.PUBLIC }
                assertThrows<IllegalArgumentException> {
                    it.addConstructor(ctor)
                }
            }
        }
    }

    @Test
    fun `empty objects`() {
        Fixture {
            addBuilder()
            val kcls = loadKClass()
            assertEquals(0, kcls.constructors.size)
            assertEquals(0, kcls.memberProperties.size)
            assertEquals(0, kcls.annotations.size)
            assertEquals(0, kcls.nestedClasses.size)
            assertTrue(kcls.isFinal)
        }
    }

    @Test
    fun `object with properties`() {
        Fixture {
            addBuilder().also {
                it.addProperty(
                    KmPropertyBuilder(
                        JavaIdName("prop"),
                        String::class.kmType,
                        String::class.kmType,
                        isVariable = false,
                        constructorProperty = false
                    ).also {
                        it.getterBody("""{return "PROP";}""")
                    }
                )
            }

            loadKClass().also { kcls ->
                kcls.shouldHaveMemberProperty("prop") {
                    assertEquals(String::class.java.typeName, it.returnType.javaType.typeName)
                    it.shouldBeImmutable()
                }

                // check that property can be statically accessed from object instance
                kcls.objectInstance!!.let {
                    val method = kcls.java.getDeclaredMethod("getProp")
                    assertEquals("PROP", method.invoke(it))
                }
            }
        }
    }

    @Test
    fun `object with interface`() {
        Fixture {
            addBuilder(kmKind = ClassKind.INTERFACE, kmName = mkKmName("MyInterface"))
            addBuilder().also {
                it.addSupertype(mkKmName("MyInterface").asType())
            }
            loadKClass().also {
                it.supertypes.shouldExist {
                    it.javaType.typeName == mkKmName("MyInterface").asJavaBinaryName.toString()
                }
            }
        }
    }

    @Test
    fun `object with base class`() {
        Fixture {
            // Throwable is chosen because it is an open class in the std lib without any
            // required constructor args or abstract members.
            val base = Throwable::class.kmType

            addBuilder().also { it.addSupertype(base) }
            loadKClass().also {
                it.supertypes.shouldExist {
                    it.javaType.typeName == base.name.asJavaBinaryName.toString()
                }
            }
        }
    }

    @Test
    fun `object with nested objects`() {
        Fixture {
            addBuilder().also {
                it.nestedClassBuilder(JavaIdName("Nested"), kind = ClassKind.OBJECT)
            }

            loadKClass().also {
                assertEquals(1, it.nestedClasses.size)
                it.nestedClasses.first().also {
                    assertEquals("Nested", it.simpleName)
                }
            }
        }
    }

    @Test
    fun `object with nested classes`() {
        Fixture {
            addBuilder().also {
                it.nestedClassBuilder(JavaIdName("Nested"), kind = ClassKind.CLASS)
            }

            loadKClass().also {
                assertEquals(1, it.nestedClasses.size)
                it.nestedClasses.first().also {
                    assertEquals("Nested", it.simpleName)
                }
            }
        }
    }

    @Test
    fun `object with methods`() {
        Fixture {
            addBuilder().also {
                it.addFun(
                    name = "myfun",
                    retType = String::class.kmType,
                    body = """{ return "MYFUN"; }"""
                )
            }

            loadKClass().also { kcls ->
                kcls.shouldHaveFunction("myfun") {
                    assertEquals(0, it.valueParameters.size)
                    assertEquals("java.lang.String", it.returnType.toString())
                }

                // check that method can be statically accessed from object instance
                kcls.objectInstance!!.let {
                    val method = kcls.java.getDeclaredMethod("myfun")
                    assertEquals("MYFUN", method.invoke(it))
                }
            }
        }
    }

    @Test
    fun `object with annotations`() {
        val msg = "MESSAGE"
        Fixture {
            addBuilder(
                annotations = setOf(
                    KmAnnotation(
                        Deprecated::class.kmName.toString(),
                        mapOf("message" to KmAnnotationArgument.StringValue(msg))
                    ) to true
                )
            )

            loadKClass().also {
                assertEquals(listOf(Deprecated(msg)), it.annotations)
            }
        }
    }
}
