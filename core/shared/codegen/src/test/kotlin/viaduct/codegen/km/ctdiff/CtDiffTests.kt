package viaduct.codegen.km.ctdiff

import actualspkg.AgreementTests
import actualspkg.DisagreementTests as DisagreementActual
import expectedspkg.DisagreementTests as DisagreementExpected
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.reflect.KClass
import org.junit.jupiter.api.Test

class CtDiffTests {
    companion object {
        const val EXP_PKG = "expectedspkg"
        const val ACT_PKG = "actualspkg"

        fun compare(
            expected: KClass<*>,
            actual: KClass<*>
        ): ClassDiff {
            val classDiff = ClassDiff(EXP_PKG, ACT_PKG)
            classDiff.compare(expected.java, actual.java)
            return classDiff
        }

        fun assertNoDiff(
            expected: KClass<*>,
            actual: KClass<*>
        ) {
            val cmp = compare(expected, actual)
            cmp.diffs.assertEmptyMultiline("No differences in expected and actual, invariant should be empty")
            cmp.elementsTested shouldBe countElements(expected.java)
        }

        fun assertDiffs(
            expectedDiffs: List<String>,
            expected: KClass<*>,
            actual: KClass<*>
        ) {
            val cmp = compare(expected, actual)
            val cmpDiffLabels = cmp.diffs.map { it.label }
            cmpDiffLabels shouldContainExactlyInAnyOrder expectedDiffs
        }

        fun countElements(c: Class<*>): Int {
            val self = 1
            val localElements = (self + c.fieldsToCompare.size + c.methodsToCompare.size + c.declaredConstructors.size)
            return c.declaredClasses.fold(localElements) { acc, clazz -> acc + countElements(clazz) }
        }
    }

    @Test
    fun `Given equivalent classes, when compared no errors should be reported`() {
        assertNoDiff(expectedspkg.AgreementTests::class, AgreementTests::class)
    }

    @Test
    fun classAnnotationMismatch() {
        assertDiffs(
            listOf("CLASS_ANNOTATIONS_AGREE"),
            DisagreementExpected.ClassAnnotationMismatch::class,
            DisagreementActual.ClassAnnotationMismatch::class
        )
    }

    @Test
    fun differentFields() {
        assertDiffs(
            listOf("CLASS_FIELDS_AGREE", "FIELD_TYPES_AGREE", "FIELD_ANNOTATIONS_AGREE", "FIELD_MODIFIERS_AGREE"),
            DisagreementExpected.DifferentFields::class,
            DisagreementActual.DifferentFields::class
        )
    }

    @Test
    fun differentMethods1() {
        assertDiffs(
            listOf("METHOD_ANNOTATIONS_AGREE", "METHOD_MODIFIERS_AGREE"),
            DisagreementExpected.DifferentMethods1::class,
            DisagreementActual.DifferentMethods1::class
        )
    }

    @Test
    fun differentMethods2() {
        assertDiffs(
            listOf("CLASS_METHODS_AGREE"),
            DisagreementExpected.DifferentMethods2::class,
            DisagreementActual.DifferentMethods2::class
        )
    }

    @Test
    fun differentMethods3() {
        assertDiffs(
            listOf("CLASS_METHODS_AGREE"),
            DisagreementExpected.DifferentMethods3::class,
            DisagreementActual.DifferentMethods3::class
        )
    }

    @Test
    fun differentMethods4() {
        assertDiffs(
            listOf("CLASS_METHODS_AGREE"),
            DisagreementExpected.DifferentMethods4::class,
            DisagreementActual.DifferentMethods4::class
        )
    }

    @Test
    fun differentMethods5() {
        assertDiffs(
            listOf("METHOD_ANNOTATIONS_AGREE"),
            DisagreementExpected.DifferentMethods5::class,
            DisagreementActual.DifferentMethods5::class
        )
    }

    @Test
    fun differentCtor1() {
        assertDiffs(
            listOf("CTOR_ANNOTATIONS_AGREE", "CTOR_MODIFIERS_AGREE", "CTOR_ANNOTATIONS_AGREE", "CTOR_MODIFIERS_AGREE"),
            DisagreementExpected.DifferentCtor1::class,
            DisagreementActual.DifferentCtor1::class
        )
    }

    @Test
    fun differentCtor2() {
        assertDiffs(
            listOf("CLASS_CTORS_AGREE"),
            DisagreementExpected.DifferentCtor2::class,
            DisagreementActual.DifferentCtor2::class
        )
    }

    @Test
    fun nestedClasses1_DiffKind() {
        assertDiffs(
            listOf("CLASS_MODIFIERS_AGREE", "CLASS_SUPERCLASS_AGREES", "CLASS_CTORS_AGREE", "CLASS_ANNOTATIONS_AGREE"),
            DisagreementExpected.NestedClasses1.DiffKind::class,
            DisagreementActual.NestedClasses1.DiffKind::class
        )
    }

    @Test
    fun nestedClasses1_DiffAnnotation() {
        assertDiffs(
            listOf("CLASS_ANNOTATIONS_AGREE"),
            DisagreementExpected.NestedClasses1.DiffAnnotation::class,
            DisagreementActual.NestedClasses1.DiffAnnotation::class
        )
    }

    @Test
    fun nestedClasses1_DiffStaticness() {
        assertDiffs(
            listOf("CLASS_MODIFIERS_AGREE", "CLASS_CTORS_AGREE", "CLASS_ANNOTATIONS_AGREE"),
            DisagreementExpected.NestedClasses1.DiffStaticness::class,
            DisagreementActual.NestedClasses1.DiffStaticness::class
        )
    }

    @Test
    fun nestedClasses2() {
        assertDiffs(
            listOf("CLASS_MODIFIERS_AGREE", "CLASS_ANNOTATIONS_AGREE", "CLASS_ANNOTATIONS_AGREE"),
            DisagreementExpected.NestedClasses2::class,
            DisagreementActual.NestedClasses2::class
        )
    }

    @Test
    fun differentInterfaces() {
        assertDiffs(
            listOf("CLASS_SUPER_INTERFACES_AGREE"),
            DisagreementExpected.DifferentInterfaces::class,
            DisagreementActual.DifferentInterfaces::class
        )
    }
}
