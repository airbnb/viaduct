package viaduct.codegen.km.ctdiff

import actualspkg.MetadataAgreementTests as Agree
import actualspkg.MetadataDisagreementTests as Disagree
import expectedspkg.MetadataAgreementTests as ExpAgree
import expectedspkg.MetadataDisagreementTests as ExpDisagree
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlin.reflect.KClass
import org.junit.jupiter.api.Test

class KmMetadataDiffTests {
    @Test
    fun testAgree() {
        val metadataDiff = compare(ExpAgree::class, Agree::class)
        metadataDiff.diffs.assertEmptyMultiline("Invariant for ExpAgree and Agree should be empty")
    }

    @Test
    fun testDisagree() {
        assertDiffs(
            listOf("KM_CLASS_MODALITY_AGREES"),
            ExpDisagree.Modality::class,
            Disagree.Modality::class
        )
        assertDiffs(
            listOf("KM_CLASS_NAMES_AGREE"),
            ExpDisagree.Names::class,
            Disagree.Namess::class
        )
        assertDiffs(
            listOf(
                "KM_CLASS_CONSTRUCTORS_AGREE",
                "KM_CONSTRUCTORS_HAS_ANNOTATIONS_AGREE",
                "KM_CONSTRUCTORS_PARAMS_DECLARES_DEFAULT_AGREE"
            ),
            ExpDisagree.Constructors::class,
            Disagree.Constructors::class
        )
        assertDiffs(
            listOf("KM_CLASS_FUNCTIONS_AGREE", "KM_FUNCTIONS_MODALITY_AGREE", "KM_FUNCTIONS_VISIBILITY_AGREE"),
            ExpDisagree.Functions::class,
            Disagree.Functions::class
        )
        assertDiffs(
            listOf(
                "KM_CLASS_PROPERTIES_AGREE",
                "KM_PROPERTIES_VISIBILITY_AGREE",
                "KM_PROPERTIES_GETTER_VISIBILITY_AGREE",
                "KM_PROPERTIES_SETTER_VISIBILITY_AGREE",
                "KM_PROPERTIES_FIELD_SIGNATURE_AGREE",
                "KM_PROPERTIES_GETTER_SIGNATURE_AGREE",
                "KM_PROPERTIES_SETTER_SIGNATURE_AGREE"
            ),
            ExpDisagree.Properties::class,
            Disagree.Properties::class
        )
        assertDiffs(
            listOf("KM_ENUM_ENTRIES_AGREE"),
            ExpDisagree.EnumEntries::class,
            Disagree.EnumEntries::class
        )
        assertDiffs(
            listOf("KM_NESTED_CLASSES_AGREE"),
            ExpDisagree::class,
            Disagree::class
        )
    }

    companion object {
        private const val EXP_PKG = "expectedspkg"
        private const val ACT_PKG = "actualspkg"

        fun assertDiffs(
            expectedDiffs: List<String>,
            expected: KClass<*>,
            actual: KClass<*>
        ) {
            val cmp = compare(expected, actual)
            val cmpDiffLabels = cmp.diffs.map { it.label }
            cmpDiffLabels shouldContainExactlyInAnyOrder expectedDiffs
        }

        private fun compare(
            expected: KClass<*>,
            actual: KClass<*>
        ): KmMetadataDiff {
            val metadataDiff = KmMetadataDiff(EXP_PKG, ACT_PKG)
            metadataDiff.compare(expected.java, actual.java)
            return metadataDiff
        }
    }
}
