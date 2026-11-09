package viaduct.codegen.km

import actualspkg.Testing
import expectedspkg.legacy.LegacyAnnotatedGetterVal
import expectedspkg.legacy.LegacyPrivateSetterVar
import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmAnnotation
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

class PrivateSetterVar {
    var a: String = TODO()
        private set
}

class AnnotatedGetterVal {
    @get:Testing
    val a: String = TODO()
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

    @Test
    fun `public var with private default setter`() {
        assertEquals(PrivateSetterVar::class.java) { builder ->
            builder.addSimpleProperty(variable = true, setterVisibility = Visibility.PRIVATE)
        }
    }

    @Test
    fun `annotated default getter`() {
        assertEquals(AnnotatedGetterVal::class.java) { builder ->
            builder.addProperty(
                KmPropertyBuilder(JavaIdName("a"), Km.STRING.asType(), Km.STRING.asType(), false, false)
                    .addGetterAnnotations(setOf(KmAnnotation("actualspkg/Testing", emptyMap()) to true))
            )
        }
    }

    @Test
    fun `Kotlin 1_9 private default setter metadata`() {
        assertEquals(LegacyPrivateSetterVar::class.java) { builder ->
            builder.addProperty(
                KmPropertyBuilder(JavaIdName("a"), Km.STRING.asType(), Km.STRING.asType(), true, false, kotlinMetadataMajorVersion = 1)
                    .setterVisibility(Visibility.PRIVATE)
            )
        }
    }

    @Test
    fun `Kotlin 1_9 annotated default getter metadata`() {
        assertEquals(LegacyAnnotatedGetterVal::class.java) { builder ->
            builder.addProperty(
                KmPropertyBuilder(JavaIdName("a"), Km.STRING.asType(), Km.STRING.asType(), false, false, kotlinMetadataMajorVersion = 1)
                    .addGetterAnnotations(setOf(KmAnnotation("actualspkg/Testing", emptyMap()) to true))
            )
        }
    }
}
