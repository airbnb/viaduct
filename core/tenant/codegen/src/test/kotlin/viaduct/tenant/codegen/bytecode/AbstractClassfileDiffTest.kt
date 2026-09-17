@file:Suppress("FunctionNaming")

package viaduct.tenant.codegen.bytecode

import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.util.function.Predicate
import javassist.ClassPool
import javassist.CtClass
import javassist.bytecode.CodeAttribute
import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.declaresDefaultValue
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.codegen.km.KmClassFilesBuilder
import viaduct.codegen.km.ctdiff.ClassDiff
import viaduct.codegen.km.ctdiff.ClassFinder
import viaduct.codegen.km.ctdiff.JavassistClassFinder
import viaduct.codegen.km.ctdiff.MethodInfo
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.ViaductSchema.Object
import viaduct.graphql.schema.ViaductSchema.TypeDef
import viaduct.graphql.schema.ViaductSchema.TypeDefKind
import viaduct.graphql.schema.test.createSchema
import viaduct.graphql.schema.test.loadGraphQLSchema
import viaduct.invariants.FailureCollector
import viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.isEligible

/** A base class for Classfile diff testing. */
abstract class AbstractClassfileDiffTest(val args: Args = Args.fromEnv()) {
    @Test
    protected fun classFileDiffTester() {
        val diffs = compareAll()
        if (!diffs.isEmpty) {
            val result = StringBuilder("Violations found:\n")
            diffs.toMultilineString(result)
            result.append("\n\nTotal errors: ${diffs.count()}")
            throw AssertionError(result.toString())
        }
    }

    fun compareAll(): FailureCollector {
        args.classes.forEach {
            args.classDiff.compare(it.expected, it.actual)
        }
        return args.classDiff.diffs
    }
}

data class Elements<T>(val expected: T, val actual: T, val def: TypeDef?) {
    fun <U> map(fn: (T) -> U): Elements<U> = Elements(fn(expected), fn(actual), def)
}
typealias Names = Elements<String>
typealias Classes = Elements<Class<*>>
typealias Resolver = (Names) -> Classes?

data class Packages(val expected: String, val actual: String) {
    companion object {
        val v0_9: Packages = Packages(
            expected = "com.airbnb.viaduct.schema.generated",
            actual = "actuals.airbnb.viaduct.schema.generated",
        )
        val v2_0: Packages = Packages(
            expected = "viaduct.api.grts",
            actual = "actuals.api.generated"
        )
    }

    fun format(
        simpleName: String,
        def: TypeDef?
    ): Names = Names("$expected.$simpleName", "$actual.$simpleName", def)
}

object ClassNames {
    val NoScalars: Predicate<TypeDef> = Predicate { it.kind != TypeDefKind.SCALAR }
    val IsEligible: Predicate<TypeDef> = Predicate { (it as? Object)?.isEligible(ViaductBaseTypeMapper(createSchema(""))) ?: true }

    fun isEligibleWith(baseTypeMapper: BaseTypeMapper): Predicate<TypeDef> = Predicate { (it as? Object)?.isEligible(baseTypeMapper) ?: true }

    fun isEligibleWith(
        baseTypeMapper: BaseTypeMapper,
        schema: ViaductSchema
    ): Predicate<TypeDef> = Predicate { (it as? Object)?.isEligible(baseTypeMapper, schema) ?: true }

    fun fromSchema(
        packages: Packages,
        typePredicate: Predicate<TypeDef> = NoScalars
    ): Sequence<Names> = fromSchema(loadGraphQLSchema(), packages, typePredicate)

    fun fromSchema(
        schema: ViaductSchema,
        packages: Packages,
        typePredicate: Predicate<TypeDef> = NoScalars,
    ): Sequence<Names> =
        schema.types.asSequence()
            .map { it.value }
            .filter(typePredicate::test)
            .flatMap {
                // add Argument names
                val argNames = if (it is Object) {
                    it.fields.filter { it.hasArgs }
                        .map { f ->
                            val argsName = cfg.argumentTypeName(f)
                            packages.format(argsName, it)
                        }
                } else {
                    emptyList()
                }

                listOf(packages.format(it.name, it)) + argNames
            }
}

object Resolvers {
    private fun ClassDiff.addFailure(name: String) {
        diffs.addFailure(
            "$name was null",
            "Class $name not found",
            emptyArray()
        )
    }

    private val Result<Class<*>>.classNotFound: Boolean get() = exceptionOrNull() is ClassNotFoundException

    private fun Names.resolve(
        classDiff: ClassDiff,
        ignorable: Boolean = false
    ): Classes? =
        map {
            runCatching { Class.forName(it) }
        }.let {
            if (it.expected.classNotFound && it.actual.classNotFound) {
                null
            } else if (it.expected.classNotFound) {
                if (!ignorable) classDiff.addFailure(expected)
                null
            } else if (it.actual.classNotFound) {
                if (!ignorable) classDiff.addFailure(actual)
                null
            } else if (ignorable) {
                // Skip comparison for ignorable types even if both classes exist
                null
            } else {
                Classes(it.expected.getOrThrow(), it.actual.getOrThrow(), it.def)
            }
        }

    class v0_9(val classDiff: ClassDiff) : Resolver {
        override fun invoke(names: Names): Classes? {
            val defName = names.def?.name
            val ignorable = defName == "Query" ||
                defName == "Mutation" ||
                defName == "Subscription" ||
                (names.def is Object && names.def.supers.any { it.name == "PagedConnection" })

            return names.resolve(classDiff, ignorable)
        }
    }

    class v2_0(val classDiff: ClassDiff) : Resolver {
        override fun invoke(names: Names): Classes? = names.resolve(classDiff)
    }
}

data class Args(
    val classDiff: ClassDiff,
    val classes: Sequence<Classes>
) {
    companion object {
        /** create Args configured using environment variables */
        fun fromEnv(): Args =
            when (val version = System.getenv("DIFF_TEST_VERSION")) {
                "v0_9" -> v0_9(Packages.v0_9, ClassNames.NoScalars.and(ClassNames.IsEligible))
                "v2_0" -> v2_0(Packages.v2_0, ClassNames.NoScalars)
                else -> throw IllegalArgumentException("unexpected DIFF_TEST_VERSION: $version")
            }

        private fun Packages.classDiff(requireInterfaceDefaults: Boolean = false): ClassDiff =
            // Add dot to the end of the package name to make sure it will replace the exact package name but not the prefix.
            // eg. replace "viaduct.api.grts.xxx" but not "viaduct.api.grtsxxx"
            ClassDiff(
                "$expected.",
                "$actual.",
                classFinder = if (requireInterfaceDefaults) {
                    GRTInterfaceDefaultExpectations("$expected.")
                } else {
                    JavassistClassFinder()
                }
            )

        /** create Args configured for typical v0.9 GRTs */
        fun v0_9(
            packages: Packages = Packages.v0_9,
            typePredicate: Predicate<TypeDef> = ClassNames.NoScalars.and(ClassNames.IsEligible),
        ): Args =
            packages.classDiff(requireInterfaceDefaults = true).let { diff ->
                Args(
                    diff,
                    ClassNames.fromSchema(packages, typePredicate)
                        .mapNotNull(Resolvers.v0_9(diff))
                )
            }

        /** create Args configured for typical v2.0 GRTs */
        fun v2_0(
            packages: Packages = Packages.v2_0,
            typePredicate: Predicate<TypeDef> = ClassNames.NoScalars,
            schemaResourcePaths: List<String> = emptyList(),
        ): Args =
            packages.classDiff().let { diff ->
                val schema = if (schemaResourcePaths.isNotEmpty()) {
                    loadGraphQLSchema(schemaResourcePaths)
                } else {
                    loadGraphQLSchema()
                }
                Args(
                    diff,
                    ClassNames.fromSchema(schema, packages, typePredicate)
                        .mapNotNull(Resolvers.v2_0(diff))
                )
            }
    }
}

/**
 * The v0.9 reference GRTs use the compiler's default JVM layout. Generated GRTs must also
 * expose native defaults and interface default-argument dispatchers. Describe those additions
 * on the expected side only: ClassDiff still checks the complete actual bytecode, including
 * the original DefaultImpls methods, annotations, and every unrelated member.
 */
private class GRTInterfaceDefaultExpectations(
    private val expectedPackage: String,
    private val delegate: ClassFinder = JavassistClassFinder()
) : ClassFinder by delegate {
    override fun getMethodSignatures(cls: Class<*>): List<MethodInfo> {
        val declared = delegate.getMethodSignatures(cls)
        if (!cls.isInterface || !cls.name.startsWith(expectedPackage)) return declared
        val defaultImpls = cls.declaredClasses.singleOrNull { it.simpleName == "DefaultImpls" } ?: return declared
        val helpers = delegate.getMethodSignatures(defaultImpls)
        val receiver = "L${cls.name.replace('.', '/')};"
        val nativeMethods = declared.map { method ->
            val helperSignature = method.signature.replaceFirst("(", "($receiver")
            if (Modifier.isAbstract(method.modifiers) && helpers.any { it.signature == helperSignature && Modifier.isStatic(it.modifiers) }) {
                method.copy(modifiers = method.modifiers and Modifier.ABSTRACT.inv())
            } else {
                method
            }
        }
        val dispatchers = helpers.filter {
            it.signature.substringBefore(' ').endsWith("\$default") &&
                it.signature.substringAfter(' ').startsWith("($receiver") &&
                Modifier.isStatic(it.modifiers) &&
                declared.none { method -> method.signature == it.signature }
        }
        return nativeMethods + dispatchers
    }
}

interface ClassfileDiffDefaults {
    fun greet(name: String = "world"): String = "hello $name"

    fun greet(value: Int): String
}

private class GRTInterfaceDefaultExpectationsTest {
    @TempDir
    lateinit var output: File

    @Test
    fun `requires and executes both default entry points`() {
        compare().assertEmptyMultiline("GRT compatibility API should agree")
        URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
            val iface = loader.loadClass(actualName)
            val receiver = Proxy.newProxyInstance(loader, arrayOf(iface)) { proxy, method, args ->
                InvocationHandler.invokeDefault(proxy, method, *(args ?: emptyArray()))
            }
            val greet = iface.getMethod("greet", String::class.java)
            assertTrue(greet.isDefault)
            assertEquals("hello supplied", greet.invoke(receiver, "supplied"))
            val helper = loader.loadClass("$actualName\$DefaultImpls")
            assertEquals("hello legacy", helper.getMethod("greet", iface, String::class.java).invoke(null, receiver, "legacy"))
            for (owner in listOf(iface, helper)) {
                val dispatcher = owner.getMethod("greet\$default", iface, String::class.java, Int::class.javaPrimitiveType, Any::class.java)
                assertEquals("hello world", dispatcher.invoke(null, receiver, null, 1, null))
            }
        }
    }

    @Test
    fun `rejects a missing native default body`() {
        val diffs = compare { iface ->
            iface.getDeclaredMethod("greet", arrayOf(iface.classPool.get("java.lang.String"))).apply {
                modifiers = modifiers or Modifier.ABSTRACT
                methodInfo.removeAttribute(CodeAttribute.tag)
            }
        }
        assertEquals(listOf("METHOD_MODIFIERS_AGREE"), diffs.map { it.label })
    }

    @Test
    fun `rejects a missing interface default argument dispatcher`() {
        val diffs = compare { iface -> iface.removeMethod(iface.getDeclaredMethod("greet\$default")) }
        assertEquals(listOf("CLASS_METHODS_AGREE"), diffs.map { it.label })
        assertTrue(diffs.single().details!!.contains("greet\$default"))
    }

    @Test
    fun `rejects a missing legacy helper`() {
        val diffs = compare { iface ->
            val helper = iface.classPool.get("$actualName\$DefaultImpls")
            helper.removeMethod(helper.getDeclaredMethod("greet"))
            helper.writeFile(output.path)
        }
        assertEquals(listOf("CLASS_METHODS_AGREE"), diffs.map { it.label })
        assertTrue(diffs.single().context.endsWith("DefaultImpls"))
    }

    @Test
    fun `abstract overload must remain abstract`() {
        val diffs = compare { iface ->
            iface.getDeclaredMethod("greet", arrayOf(CtClass.intType)).setBody("{ return \"unexpected\"; }")
        }
        assertEquals(listOf("METHOD_MODIFIERS_AGREE"), diffs.map { it.label })
    }

    private val actualName = "actuals.grtdiff.ClassfileDiffDefaults"

    private fun compare(mutate: (CtClass) -> Unit = {}): FailureCollector {
        val builders = KmClassFilesBuilder()
        builders.customClassBuilder(ClassKind.INTERFACE, KmName(actualName.replace('.', '/'))).apply {
            addFunction(
                KmFunction("greet").apply {
                    visibility = Visibility.PUBLIC
                    modality = Modality.OPEN
                    returnType = Km.STRING.asType()
                    valueParameters.add(
                        KmValueParameter("name").apply {
                            type = Km.STRING.asType()
                            declaresDefaultValue = true
                        }
                    )
                },
                body = "{ return \"hello \" + $2; }",
                defaultParamValues = mapOf(JavaIdName("name") to "\"world\"")
            )
            addFunction(
                KmFunction("greet").apply {
                    visibility = Visibility.PUBLIC
                    modality = Modality.ABSTRACT
                    returnType = Km.STRING.asType()
                    valueParameters.add(KmValueParameter("value").apply { type = Km.INT.asType() })
                }
            )
        }
        builders.buildClassfiles(output)
        val pool = ClassPool(true).apply { insertClassPath(output.path) }
        val iface = pool.get(actualName)
        mutate(iface)
        iface.writeFile(output.path)
        return URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
            val expected = ClassfileDiffDefaults::class.java
            ClassDiff(
                "${expected.packageName}.",
                "actuals.grtdiff.",
                classFinder = GRTInterfaceDefaultExpectations("${expected.packageName}.", JavassistClassFinder(pool, loader))
            ).apply { compare(expected, loader.loadClass(actualName)) }.diffs
        }
    }
}

private class A

private class B

private class ClassfileDiffSanityTest {
    private class DiffTest(args: Args) : AbstractClassfileDiffTest(args)

    @Test
    fun `compareAll -- empty`() {
        val diffs = DiffTest(
            Args(
                ClassDiff("a", "b"),
                emptySequence()
            )
        ).compareAll()
        assertTrue(diffs.isEmpty)
    }

    @Test
    fun `compareAll -- same`() {
        val diffs = DiffTest(
            Args(
                ClassDiff("actual", "actual"),
                sequenceOf(
                    Classes(A::class.java, A::class.java, null),
                    Classes(B::class.java, B::class.java, null)
                )
            )
        ).compareAll()
        assertTrue(diffs.isEmpty)
    }

    @Test
    fun `compareAll -- different`() {
        val diffs = DiffTest(
            Args(
                ClassDiff("actual", "actual"),
                sequenceOf(
                    Classes(A::class.java, B::class.java, null)
                )
            )
        ).compareAll()
        assertTrue(diffs.size > 0)
    }

    @Test
    fun `ClassNames -- fromSchema`() {
        val names = ClassNames.fromSchema(
            createSchema(
                """
                type Sentinel { x: Int }
                type Foo { x: Int }
                type Bar { x(y: Int): Int }
                """.trimIndent()
            ),
            Packages("a", "b"),
            typePredicate = { it.name != "Foo" }
        )
        assertTrue(names.any { it.def!!.name == "Sentinel" })
        assertFalse(names.any { it.def?.name == "Foo" })
        assertTrue(names.any { it.expected == "a.Bar_X_Arguments" })
    }

    @Test
    fun `ClassNames -- NoScalars`() {
        val names = ClassNames.fromSchema(
            createSchema(
                """
                    type Sentinel { x: Int }
                    scalar A
                """.trimIndent()
            ),
            Packages("a", "b"),
            ClassNames.NoScalars
        )
        assertTrue(names.any { it.def!!.name == "Sentinel" })
        assertFalse(names.any { it.def!!.kind == TypeDefKind.SCALAR })
    }

    @Test
    fun `ClassNames -- IsEligible`() {
        val names = ClassNames.fromSchema(
            createSchema(
                """
                    type Sentinel { x: Int }
                    interface PagedConnection { x: Int }
                    type MyConnection implements PagedConnection { x: Int }
                """.trimIndent()
            ),
            Packages("a", "b"),
            ClassNames.IsEligible
        )

        assertTrue(names.any { it.def!!.name == "Sentinel" })
        assertFalse(names.any { it.def!!.name == "MyConnection" })
        assertTrue(names.any { it.def!!.name == "Query" })
    }

    @Test
    fun `Packages -- format`() {
        // no def
        Packages("a", "b").format("Foo", null).let {
            assertEquals(Names("a.Foo", "b.Foo", null), it)
        }

        // with def
        createSchema("type Foo { x: Int }").types["Foo"]!!.let { foo ->
            Packages("a", "b").format("Foo", foo).let {
                assertEquals(Names("a.Foo", "b.Foo", foo), it)
            }
        }
    }

    private fun testResolver(mkResolver: (ClassDiff) -> Resolver) {
        // neither class is resolvable
        ClassDiff("a", "b").let { diff ->
            val resolved = mkResolver(diff)
                .invoke(Names("a.A", "b.A", null))

            assertTrue(resolved == null)
            assertTrue(diff.diffs.isEmpty)
        }

        // both classes are resolvable
        ClassDiff("a", "b").let { diff ->
            val resolved = mkResolver(diff)
                .invoke(Names(A::class.qualifiedName!!, B::class.qualifiedName!!, null))

            assertTrue(resolved != null)
            assertTrue(diff.diffs.isEmpty)
        }

        // one class cannot be resolved
        ClassDiff("a", "b").let { diff ->
            val resolved = mkResolver(diff)
                .invoke(Names(A::class.qualifiedName!!, "b.A", null))

            assertTrue(resolved == null)
            assertTrue(diff.diffs.size > 0)
        }
    }

    @Test
    fun `Resolvers -- v0_9`() {
        testResolver(Resolvers::v0_9)
    }

    @Test
    fun `Resolvers -- v2_0`() {
        testResolver(Resolvers::v2_0)
    }
}
