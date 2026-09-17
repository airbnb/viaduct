package viaduct.codegen.km

import java.io.ByteArrayInputStream
import javassist.ClassPool
import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.KmVariance
import kotlinx.metadata.Visibility
import kotlinx.metadata.declaresDefaultValue
import kotlinx.metadata.jvm.KotlinClassMetadata
import kotlinx.metadata.jvm.hasMethodBodiesInInterface
import kotlinx.metadata.jvm.isCompiledInCompatibilityMode
import kotlinx.metadata.visibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.utils.JavaBinaryName
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName

interface CompatibilityDefaults {
    fun greet(name: String = "world"): String = "hello $name"
}

interface CompatibilityChildDefaults : CompatibilityDefaults {
    override fun greet(name: String): String = "child $name"
}

interface CompatibilityGenericDefaults<T> {
    fun echo(value: T): T = value
}

interface CompatibilityAbstractOverride : CompatibilityDefaults {
    override fun greet(name: String): String
}

interface CompatibilityRuntimeInterface {
    fun answer(): String = "compiled"
}

class CompatibilityCompiledConsumer : CompatibilityRuntimeInterface

interface CompatibilityRuntimeWithArgs {
    fun greet(name: String = "world"): String = "compiled $name"
}

class CompatibilityCompiledCaller {
    fun call(receiver: CompatibilityRuntimeWithArgs): String = receiver.greet()
}

class DefaultMethodCompatibilityTest {
    @Test
    fun `compiled implementation links against generated interface`() {
        val builders = KmClassFilesBuilder()
        val generatedName = KmName("compatibility/runtime/RuntimeInterface")
        builders.customClassBuilder(ClassKind.INTERFACE, generatedName).apply {
            addFun("answer", Km.STRING.asType(), body = "{ return \"generated\"; }")
        }
        val loader = builders.buildClassLoader()
        val iface = loader.loadClass(generatedName.asJavaBinaryName.toString())
        assertTrue(iface.getMethod("answer").isDefault)
        val helper = loader.loadClass("${iface.name}\$DefaultImpls")
        assertEquals("generated", helper.getMethod("answer", iface).invoke(null, null))
        for (inheritNativeBody in listOf(false, true)) {
            val consumer = loader.reload(
                CompatibilityCompiledConsumer::class.java,
                CompatibilityRuntimeInterface::class.java.name,
                generatedName.asJavaBinaryName.toString(),
                inheritNativeBody
            )
            val instance = consumer.getDeclaredConstructor().newInstance()
            assertEquals("generated", iface.getMethod("answer").invoke(instance))
        }
        val metadata = KotlinClassMetadata.readStrict(iface.getAnnotation(Metadata::class.java)) as KotlinClassMetadata.Class
        assertTrue(metadata.kmClass.hasMethodBodiesInInterface)
        assertTrue(metadata.kmClass.isCompiledInCompatibilityMode)
    }

    @Test
    fun `compiled default argument call links against generated interface`() {
        val builders = KmClassFilesBuilder()
        val generatedName = KmName("compatibility/runtime/RuntimeWithArgs")
        builders.customClassBuilder(ClassKind.INTERFACE, generatedName).apply {
            addFunction(
                KmFunction("greet").apply {
                    visibility = Visibility.PUBLIC
                    returnType = Km.STRING.asType()
                    valueParameters.add(
                        KmValueParameter("name").apply {
                            type = Km.STRING.asType()
                            declaresDefaultValue = true
                        }
                    )
                },
                body = "{ return \"generated \" + $2; }",
                defaultParamValues = mapOf(JavaIdName("name") to "\"world\"")
            )
        }
        builders.customClassBuilder(ClassKind.CLASS, KmName("compatibility/RuntimeWithArgs")).apply {
            addSupertype(generatedName.asType())
            addEmptyCtor()
        }
        val loader = builders.buildClassLoader()
        val caller = loader.reload(CompatibilityCompiledCaller::class.java, CompatibilityRuntimeWithArgs::class.java.name, generatedName.asJavaBinaryName.toString())
        val iface = loader.loadClass(generatedName.asJavaBinaryName.toString())
        val instance = loader.loadClass("compatibility.RuntimeWithArgs").getDeclaredConstructor().newInstance()
        assertEquals("generated world", caller.getMethod("call", iface).invoke(caller.getDeclaredConstructor().newInstance(), instance))
        // Exercise both dispatcher ABIs even when this test's compiler emits only one of them.
        for (owner in listOf(iface, loader.loadClass("${iface.name}\$DefaultImpls"))) {
            val dispatcher = owner.getMethod("greet\$default", iface, String::class.java, Int::class.javaPrimitiveType, Any::class.java)
            assertEquals("generated world", dispatcher.invoke(null, instance, null, 1, null))
            assertEquals("generated supplied", dispatcher.invoke(null, instance, "supplied", 0, null))
        }
    }

    @Test
    fun `void defaults and primitive default arguments support both entry points`() {
        val builders = KmClassFilesBuilder()
        val name = KmName("compatibility/VoidDefaults")
        builders.customClassBuilder(ClassKind.INTERFACE, name).apply {
            addFunction(
                KmFunction("accept").apply {
                    visibility = Visibility.PUBLIC
                    returnType = Km.UNIT.asType()
                    valueParameters.add(
                        KmValueParameter("value").apply {
                            type = Km.INT.asType()
                            declaresDefaultValue = true
                        }
                    )
                },
                body = "{ if ($2 != 7) throw new IllegalArgumentException(); }",
                defaultParamValues = mapOf(JavaIdName("value") to "7")
            )
        }
        builders.customClassBuilder(ClassKind.CLASS, KmName("compatibility/VoidImplementation")).apply {
            addSupertype(name.asType())
            addEmptyCtor()
        }
        val loader = builders.buildClassLoader()
        val iface = loader.loadClass("compatibility.VoidDefaults")
        val instance = loader.loadClass("compatibility.VoidImplementation").getDeclaredConstructor().newInstance()
        iface.getMethod("accept", Int::class.javaPrimitiveType).invoke(instance, 7)
        for (owner in listOf(iface, loader.loadClass("${iface.name}\$DefaultImpls"))) {
            owner.getMethod("accept\$default", iface, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Any::class.java)
                .invoke(null, instance, 0, 1, null)
        }
    }

    @Test
    fun `generated implementation inherits default body and default argument`() {
        val instance = implementation("InheritedDefaults", CompatibilityDefaults::class.kmType) as CompatibilityDefaults
        assertEquals("hello world", instance.greet())
        assertEquals("hello supplied", instance.greet("supplied"))
    }

    @Test
    fun `generated implementation uses most specific override`() {
        val instance = implementation("ChildDefaults", CompatibilityChildDefaults::class.kmType) as CompatibilityDefaults
        assertEquals("child world", instance.greet())
        assertEquals("child supplied", instance.greet("supplied"))
    }

    @Test
    fun `generated implementation inherits specialized generic default`() {
        @Suppress("UNCHECKED_CAST")
        val instance = implementation(
            "GenericDefaults",
            CompatibilityGenericDefaults::class.kmType.also {
                it.arguments.add(KmTypeProjection(KmVariance.INVARIANT, Km.STRING.asType()))
            }
        ) as CompatibilityGenericDefaults<String>
        assertEquals("value", instance.echo("value"))
    }

    @Test
    fun `generated override takes precedence over interface default`() {
        val builders = KmClassFilesBuilder()
        builders.customClassBuilder(ClassKind.CLASS, KmName("compatibility/OverrideDefaults")).apply {
            addSupertype(
                CompatibilityGenericDefaults::class.kmType.also {
                    it.arguments.add(KmTypeProjection(KmVariance.INVARIANT, Km.STRING.asType()))
                }
            )
            addEmptyCtor()
            addFun(
                "echo",
                Km.ANY.asNullableType(),
                listOf(
                    KmValueParameter("value").apply {
                        type = Km.ANY.asNullableType()
                    }
                ),
                "{ return \"override\"; }"
            )
        }
        @Suppress("UNCHECKED_CAST")
        val instance = builders.loadClass(JavaBinaryName("compatibility.OverrideDefaults"))
            .getDeclaredConstructor().newInstance() as CompatibilityGenericDefaults<String>
        assertEquals("override", instance.echo("value"))
    }

    @Test
    fun `abstract override does not silently inherit removed default`() {
        val instance = implementation("AbstractOverride", CompatibilityAbstractOverride::class.kmType) as CompatibilityDefaults
        assertThrows(AbstractMethodError::class.java) { instance.greet("value") }
    }

    private fun implementation(
        name: String,
        supertype: KmType
    ): Any {
        val builders = KmClassFilesBuilder()
        builders.customClassBuilder(ClassKind.CLASS, KmName("compatibility/$name")).apply {
            addSupertype(supertype)
            addEmptyCtor()
        }
        return builders.loadClass(JavaBinaryName("compatibility.$name")).getDeclaredConstructor().newInstance()
    }

    private fun ClassLoader.reload(
        type: Class<*>,
        originalInterface: String,
        generatedInterface: String,
        inheritNativeBody: Boolean = false
    ): Class<*> {
        // Relocate references so compiled helper classes on the test classpath cannot shadow generated helpers.
        val originalBytes = type.getResourceAsStream("/${type.name.replace('.', '/')}.class")!!.use { it.readBytes() }
        val relocated = ClassPool(false).makeClass(ByteArrayInputStream(originalBytes))
        relocated.replaceClassName(originalInterface, generatedInterface)
        relocated.replaceClassName("$originalInterface\$DefaultImpls", "$generatedInterface\$DefaultImpls")
        if (inheritNativeBody) {
            // A modern implementation has no delegation stub; resolution must find the interface body.
            relocated.declaredMethods.forEach { relocated.removeMethod(it) }
        }
        val bytes = relocated.toBytecode()
        return object : ClassLoader(this) {
            fun loadCopy(): Class<*> = defineClass(type.name, bytes, 0, bytes.size)
        }.loadCopy()
    }
}
