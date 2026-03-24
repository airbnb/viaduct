package viaduct.codegen.km

import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.Visibility
import kotlinx.metadata.visibility
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName

// test that types are able to define methods and properties that
// can be compiled against the methods/properties of other generated types

class InterdependentCompilationTest {
    @Test
    fun `interdependent class functions`() {
        // Generate classes that look like:
        //   class Foo { fun bar(): Bar = new Bar() }
        //   class Bar { fun foo(): Foo = new Foo() }
        val kmCtx = KmClassFilesBuilder()
        kmCtx
            .customClassBuilder(ClassKind.CLASS, KmName("pkg/Foo"))
            .apply {
                addEmptyCtor()
                addFunction(
                    KmFunction("bar").apply {
                        visibility = Visibility.PUBLIC
                        returnType = KmName("pkg/Bar").asType()
                    },
                    "{ return new pkg.Bar(); }"
                )
            }
        kmCtx
            .customClassBuilder(ClassKind.CLASS, KmName("pkg/Bar"))
            .apply {
                addEmptyCtor()
                addFunction(
                    KmFunction("foo").apply {
                        visibility = Visibility.PUBLIC
                        returnType = KmName("pkg/Foo").asType()
                    },
                    "{ return new pkg.Foo(); }"
                )
            }

        val loader = kmCtx.buildClassLoader()
        val fooCls = loader.loadClass("pkg.Foo")
        val barCls = loader.loadClass("pkg.Bar")

        val foo = fooCls.declaredConstructors.first { it.parameterCount == 0 }.newInstance()
        val getBar = fooCls.getDeclaredMethod("bar")
        val bar = getBar.invoke(foo)
        assertTrue(barCls.isInstance(bar))

        val foo2 = barCls.getDeclaredMethod("foo").invoke(bar)
        assertTrue(fooCls.isInstance(foo2))
    }

    @Test
    fun `interdependent properties`() {
        // Generate classes that look like:
        //   class Foo { val bar: Bar get() = new Bar() }
        //   class Bar { val foo: Foo get() = new Foo() }
        val kmCtx = KmClassFilesBuilder()
        kmCtx
            .customClassBuilder(ClassKind.CLASS, KmName("Foo"))
            .apply {
                addEmptyCtor()
                addProperty(
                    KmPropertyBuilder(
                        JavaIdName("bar"),
                        KmName("Bar").asType(),
                        KmName("Bar").asType(),
                        isVariable = false,
                        constructorProperty = false
                    ).apply {
                        getterVisibility(Visibility.PUBLIC)
                        getterBody("{ return new Bar(); }")
                    }
                )
            }
        kmCtx
            .customClassBuilder(ClassKind.CLASS, KmName("Bar"))
            .apply {
                addEmptyCtor()
                addProperty(
                    KmPropertyBuilder(
                        JavaIdName("foo"),
                        KmName("Foo").asType(),
                        KmName("Foo").asType(),
                        isVariable = false,
                        constructorProperty = false
                    ).apply {
                        getterVisibility(Visibility.PUBLIC)
                        getterBody("{ return new Foo(); }")
                    }
                )
            }

        val loader = kmCtx.buildClassLoader()
        val fooCls = loader.loadClass("Foo")
        val barCls = loader.loadClass("Bar")

        val foo = fooCls.declaredConstructors.first { it.parameterCount == 0 }.newInstance()
        val getBar = fooCls.getDeclaredMethod("getBar")
        val bar = getBar.invoke(foo)
        assertTrue(barCls.isInstance(bar))

        val foo2 = barCls.getDeclaredMethod("getFoo").invoke(bar)
        assertTrue(fooCls.isInstance(foo2))
    }

    @Test
    fun `interdependent constructors`() {
        // Generate classes that look like:
        //   class Foo { init { Bar(1) } }
        //   class Bar(val x: Int)
        val kmCtx = KmClassFilesBuilder()
        kmCtx
            .customClassBuilder(ClassKind.CLASS, KmName("Foo"))
            .apply {
                addConstructor(
                    KmConstructor()
                        .apply {
                            visibility = Visibility.PUBLIC
                        },
                    body = "{ new Bar(1); }"
                )
            }
        kmCtx
            .customClassBuilder(ClassKind.CLASS, KmName("Bar"))
            .apply {
                addConstructor(
                    KmConstructor().apply {
                        visibility = Visibility.PUBLIC
                        valueParameters +=
                            KmValueParameter("x").apply {
                                type = Km.INT.asType()
                            }
                    },
                    body = "{}"
                )
            }

        val loader = kmCtx.buildClassLoader()
        val fooCls = loader.loadClass("Foo")

        val foo = fooCls.declaredConstructors.first { it.parameterCount == 0 }.newInstance()
        assertTrue(fooCls.isInstance(foo))
    }
}
