package viaduct.codegen.km

import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmType
import kotlinx.metadata.Visibility
import org.junit.jupiter.api.Test
import viaduct.codegen.km.ctdiff.ClassDiff
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName

class PrivateVal {
    private val a: String = TODO()
}

class ProtectedVal {
    protected val a: String = TODO()
}

class PublicVal {
    val a: String = TODO()
}

class PrivateVar {
    private var a: String = TODO()
}

class ProtectedVar {
    protected var a: String = TODO()
}

class PublicVar {
    var a: String = TODO()
}

private const val actualPkg = "actual"

class PropertiesTest {
    private fun assertEquals(
        exp: Class<*>,
        build: (CustomClassBuilder) -> Unit
    ) {
        val builders = KmClassFilesBuilder()

        val builder = CustomClassBuilder(ClassKind.CLASS, KmName("$actualPkg/${exp.simpleName}"))
        builder.addEmptyCtor()
        build(builder)

        builders.addBuilder(builder)
        val loader = builders.buildClassLoader()
        val cls = loader.loadClass(builder.kmName.asJavaBinaryName.toString())

        ClassDiff(
            expectedPkg = exp.canonicalName.substringBeforeLast('.'),
            actualPkg = actualPkg,
            javassistPool = builders.classPool
        ).let { diff ->
            diff.compare(exp, cls)
            diff.diffs.assertEmpty("\n")
        }
    }

    private fun CustomClassBuilder.addSimpleProperty(
        variable: Boolean = false,
        getterVisibility: Visibility = Visibility.PUBLIC,
        setterVisibility: Visibility? = null,
        name: String = "a",
        type: KmType = Km.STRING.asType(),
        constructorProperty: Boolean = false
    ): CustomClassBuilder =
        this.addProperty(
            KmPropertyBuilder(JavaIdName(name), type, type, variable, constructorProperty).also {
                it.getterVisibility(getterVisibility)
                if (setterVisibility != null) {
                    it.setterVisibility(setterVisibility)
                }
            }
        )

    @Test
    fun `private val`() {
        assertEquals(PrivateVal::class.java) { builder ->
            builder.addSimpleProperty(getterVisibility = Visibility.PRIVATE)
        }
    }

    @Test
    fun `protected val`() {
        assertEquals(ProtectedVal::class.java) { builder ->
            builder.addSimpleProperty(getterVisibility = Visibility.PROTECTED)
        }
    }

    @Test
    fun `public val`() {
        assertEquals(PublicVal::class.java) { builder ->
            builder.addSimpleProperty(getterVisibility = Visibility.PUBLIC)
        }
    }

    @Test
    fun `private var`() {
        assertEquals(PrivateVar::class.java) { builder ->
            builder.addSimpleProperty(
                variable = true,
                getterVisibility = Visibility.PRIVATE,
                setterVisibility = Visibility.PRIVATE
            )
        }
    }

    @Test
    fun `protected var`() {
        assertEquals(ProtectedVar::class.java) { builder ->
            builder.addSimpleProperty(
                variable = true,
                getterVisibility = Visibility.PROTECTED,
                setterVisibility = Visibility.PROTECTED
            )
        }
    }

    @Test
    fun `public var`() {
        assertEquals(PublicVar::class.java) { builder ->
            builder.addSimpleProperty(
                variable = true,
                getterVisibility = Visibility.PUBLIC,
                setterVisibility = Visibility.PUBLIC
            )
        }
    }
}
