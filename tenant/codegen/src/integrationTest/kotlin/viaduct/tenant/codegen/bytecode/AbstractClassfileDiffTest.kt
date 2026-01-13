@file:Suppress("FunctionNaming")

package viaduct.tenant.codegen.bytecode

import java.util.function.Predicate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.km.ctdiff.ClassDiff
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.ViaductSchema.Object
import viaduct.graphql.schema.ViaductSchema.TypeDef
import viaduct.graphql.schema.ViaductSchema.TypeDefKind
import viaduct.graphql.schema.test.loadGraphQLSchema
import viaduct.graphql.schema.test.mkSchema
import viaduct.invariants.InvariantChecker
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

    fun compareAll(): InvariantChecker {
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
    val IsEligible: Predicate<TypeDef> = Predicate { (it as? Object)?.isEligible(ViaductBaseTypeMapper(mkSchema(""))) ?: true }

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

        private fun Packages.classDiff(): ClassDiff =
            // Add dot to the end of the package name to make sure it will replace the exact package name but not the prefix.
            // eg. replace "viaduct.api.grts.xxx" but not "viaduct.api.grtsxxx"
            ClassDiff("$expected.", "$actual.")

        /** create Args configured for typical v0.9 GRTs */
        fun v0_9(
            packages: Packages = Packages.v0_9,
            typePredicate: Predicate<TypeDef> = ClassNames.NoScalars.and(ClassNames.IsEligible),
        ): Args =
            packages.classDiff().let { diff ->
                Args(
                    diff,
                    ClassNames.fromSchema(packages, typePredicate)
                        .mapNotNull(Resolvers.v0_9(diff))
                )
            }

        /** create Args configured for typical v2.0 GRTs */
        fun v2_0(
            packages: Packages = Packages.v2_0,
            typePredicate: Predicate<TypeDef> = ClassNames.NoScalars
        ): Args =
            packages.classDiff().let { diff ->
                Args(
                    diff,
                    (ClassNames.fromSchema(packages, typePredicate))
                        .mapNotNull(Resolvers.v2_0(diff))
                )
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
            mkSchema(
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
            mkSchema(
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
            mkSchema(
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
        mkSchema("type Foo { x: Int }").types["Foo"]!!.let { foo ->
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
