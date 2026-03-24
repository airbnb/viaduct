package viaduct.codegen.km

import javassist.ClassPool
import javassist.bytecode.AccessFlag
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.Visibility
import kotlinx.metadata.visibility
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.km.ctdiff.ClassDiff
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName

// the types of these value params are selected to exercise primitives and boxing --
// a non-nullable Int is represented by a primitive int, whereas a nullable Int
// is represented as a boxed java.lang.Integer

@Suppress("UNUSED_PARAMETER")
class Outer private constructor(
    a: Int,
    b: Int?
) {
    @Suppress("UNUSED")
    class Inner {
        fun build(): Outer = Outer(1, null)
    }
}

private const val actualPkg = "actual"

class SyntheticAccessorTest {
    private class Fixture {
        val builders = KmClassFilesBuilder()

        val outer: Class<*>
        val pool: ClassPool
            get() = builders.classPool

        init {
            builders.addBuilder(
                CustomClassBuilder(ClassKind.CLASS, KmName("$actualPkg/Outer"))
                    .also { outer ->
                        outer.addConstructor(
                            KmConstructor()
                                .apply {
                                    visibility = Visibility.PRIVATE
                                    valueParameters +=
                                        KmValueParameter("a").apply {
                                            type = Km.INT.asType()
                                        }
                                    valueParameters +=
                                        KmValueParameter("b").apply {
                                            type = Km.INT.asNullableType()
                                        }
                                },
                            body = "{}",
                            genSyntheticAccessor = true
                        )
                        outer
                            .nestedClassBuilder(JavaIdName("Inner"))
                            .also { inner ->
                                inner.addConstructor(
                                    KmConstructor().apply {
                                        visibility = Visibility.PUBLIC
                                    },
                                    body = "{}"
                                )
                                inner.addFunction(
                                    KmFunction("build").apply {
                                        visibility = Visibility.PUBLIC
                                        returnType = outer.kmType
                                    },
                                    // NB: the last `null` value is for DefaultConstructorMarker;
                                    // providing a value for it ensures that we invoke the public synthetic
                                    // accessor.
                                    "{return new $actualPkg.Outer(1, null, null);}"
                                )
                            }
                    }
            )
            val loader = builders.buildClassLoader()
            outer = loader.loadClass("$actualPkg.Outer")
        }
    }

    @Test
    fun `generates synth accessors`() {
        val cls = Fixture().outer
        assertNotNull(
            cls.declaredConstructors.firstOrNull { ctor ->
                val modifiers = (ctor.modifiers and (AccessFlag.PUBLIC or AccessFlag.SYNTHETIC)) != 0
                val marker = ctor.parameters.last().type == DefaultConstructorMarker::class.java
                modifiers && marker
            }
        )
    }

    @Test
    fun `matches expected`() {
        Fixture().let { fixture ->
            ClassDiff(
                expectedPkg = "viaduct.codegen.km",
                actualPkg = actualPkg,
                javassistPool = fixture.pool
            ).let { diff ->
                diff.compare(Outer::class.java, fixture.outer)
                diff.diffs.assertEmpty("\n")
            }
        }
    }

    @Test
    fun `accessors can be invoked`() {
        val outerCls = Fixture().outer
        val innerCls = outerCls.classes.first()

        val inner = innerCls.getDeclaredConstructor().newInstance()
        val outer = innerCls.getMethod("build").invoke(inner)

        assertTrue(outerCls.isInstance(outer))
    }
}
