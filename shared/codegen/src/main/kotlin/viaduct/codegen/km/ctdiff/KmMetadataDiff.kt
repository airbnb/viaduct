package viaduct.codegen.km.ctdiff

import kotlinx.metadata.KmClass
import kotlinx.metadata.KmClassifier
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmProperty
import kotlinx.metadata.KmType
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.KmVariance
import kotlinx.metadata.declaresDefaultValue
import kotlinx.metadata.hasAnnotations
import kotlinx.metadata.hasConstant
import kotlinx.metadata.hasEnumEntries
import kotlinx.metadata.hasNonStableParameterNames
import kotlinx.metadata.isConst
import kotlinx.metadata.isCrossinline
import kotlinx.metadata.isData
import kotlinx.metadata.isDelegated
import kotlinx.metadata.isExpect
import kotlinx.metadata.isExternal
import kotlinx.metadata.isFunInterface
import kotlinx.metadata.isInfix
import kotlinx.metadata.isInline
import kotlinx.metadata.isInner
import kotlinx.metadata.isLateinit
import kotlinx.metadata.isNoinline
import kotlinx.metadata.isNotDefault
import kotlinx.metadata.isOperator
import kotlinx.metadata.isSecondary
import kotlinx.metadata.isSuspend
import kotlinx.metadata.isTailrec
import kotlinx.metadata.isValue
import kotlinx.metadata.isVar
import kotlinx.metadata.jvm.JvmFieldSignature
import kotlinx.metadata.jvm.JvmMethodSignature
import kotlinx.metadata.jvm.KotlinClassMetadata
import kotlinx.metadata.jvm.fieldSignature
import kotlinx.metadata.jvm.getterSignature
import kotlinx.metadata.jvm.setterSignature
import kotlinx.metadata.jvm.signature
import kotlinx.metadata.kind
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.invariants.InvariantChecker

class KmMetadataDiff(
    val expectedPkg: String,
    val actualPkg: String,
    val diffs: InvariantChecker = InvariantChecker()
) {
    val expectedPkgKm = expectedPkg.replace(".", "/")
    val actualPkgKm = actualPkg.replace(".", "/")

    fun compare(
        expected: Class<*>,
        actual: Class<*>
    ) {
        val expClassMetadata = expected.annotations.filterIsInstance<Metadata>()
        val actClassMetadata = actual.annotations.filterIsInstance<Metadata>()
        // CtDiffTests use java classes without kotlin metadata
        if (expClassMetadata.isEmpty() && actClassMetadata.isEmpty()) return

        val expectedParsedMetadataList = expClassMetadata.map { parseMetadata(it) }
        if (expectedParsedMetadataList.size != 1) {
            throw RuntimeException(
                "Expected exactly 1 metadata annotation that parses to KotlinClassMetadata"
            )
        }
        val expectedParsedMetadata = expectedParsedMetadataList.first()

        val actualParsedMetadataList = actClassMetadata.map { parseMetadata(it) }
        if (!diffs.isEqualTo(actualParsedMetadataList.size, 1, "NUM_METADATA_ANNOTATIONS")) return
        val actualParsedMetadata = actualParsedMetadataList.first()

        when (expectedParsedMetadata) {
            is KotlinClassMetadata.Class -> {
                if (!diffs.isInstanceOf(
                        KotlinClassMetadata.Class::class,
                        actualParsedMetadata,
                        "METADATA_IS_CLASS_TYPE"
                    )
                ) {
                    return
                }
                val actualKmClass = (actualParsedMetadata as KotlinClassMetadata.Class).kmClass
                compareKmClasses(expectedParsedMetadata.kmClass, actualKmClass)
            }
            is KotlinClassMetadata.SyntheticClass -> {
                if (expectedParsedMetadata.isLambda) {
                    throw RuntimeException(
                        "Unexpected lambda synthetic class metadata annotation type"
                    )
                }
                diffs.isInstanceOf(
                    KotlinClassMetadata.SyntheticClass::class,
                    actualParsedMetadata,
                    "METADATA_IS_SYNTHETIC_CLASS_TYPE"
                )
            }
            else -> throw RuntimeException("Unexpected parsed KotlinClassMetadata subclass: $expectedParsedMetadata")
        }
    }

    private fun compareKmClasses(
        expected: KmClass,
        actual: KmClass
    ) {
        // Class attributes
        diffs.isEqualTo(expected.modality, actual.modality, "KM_CLASS_MODALITY_AGREES")
        diffs.isEqualTo(expected.visibility, actual.visibility, "KM_CLASS_VISIBILITY_AGREES")
        diffs.isEqualTo(expected.kind, actual.kind, "KM_CLASS_KIND_AGREES")
        diffs.isEqualTo(expected.isInner, actual.isInner, "KM_CLASS_IS_INNER_AGREES")
        diffs.isEqualTo(expected.isData, actual.isData, "KM_CLASS_IS_DATA_AGREES")
        diffs.isEqualTo(expected.isExternal, actual.isExternal, "KM_CLASS_IS_EXTERNAL_AGREES")
        diffs.isEqualTo(expected.isExpect, actual.isExpect, "KM_CLASS_IS_EXPECT_AGREES")
        diffs.isEqualTo(expected.isValue, actual.isValue, "KM_CLASS_IS_VALUE_AGREES")
        diffs.isEqualTo(expected.isFunInterface, actual.isFunInterface, "KM_CLASS_IS_FUN_INTERFACE_AGREES")
        diffs.isEqualTo(expected.hasEnumEntries, actual.hasEnumEntries, "KM_CLASS_HAS_ENUM_ENTRIES_AGREES")
        diffs.isEqualTo(expected.hasAnnotations, actual.hasAnnotations, "KM_CLASS_HAS_ANNOTATIONS_AGREES")

        diffs.isEqualTo(expected.name.replace(expectedPkgKm, actualPkgKm), actual.name, "KM_CLASS_NAMES_AGREE")
        diffs.containsExactlyElementsIn(
            expected.supertypes.map { kmTypeToString(it) },
            actual.supertypes.map { kmTypeToString(it) },
            "KM_CLASS_SUPERTYPES_AGREE"
        )

        compareElements(
            expected.constructors.map { Comparable(it.also { it.signature = updateSignaturePkg(it.signature) }) },
            actual.constructors.map { Comparable(it) },
            "CONSTRUCTORS"
        )
        compareElements(
            expected.functions.map { Comparable(it.also { it.signature = updateSignaturePkg(it.signature) }) },
            actual.functions.map { Comparable(it) },
            "FUNCTIONS"
        )
        compareElements(
            expected.properties.map {
                Comparable(
                    it.also {
                        it.fieldSignature = updateSignaturePkg(it.fieldSignature)
                        it.getterSignature = updateSignaturePkg(it.getterSignature)
                        it.setterSignature = updateSignaturePkg(it.setterSignature)
                    }
                )
            },
            actual.properties.map { Comparable(it) },
            "PROPERTIES"
        )
        diffs.containsExactlyElementsIn(
            expected.nestedClasses.sorted(),
            actual.nestedClasses.sorted(),
            "KM_NESTED_CLASSES_AGREE"
        )
        diffs.containsExactlyElementsIn(expected.enumEntries, actual.enumEntries, "KM_ENUM_ENTRIES_AGREE")
    }

    private fun compareElements(
        expected: List<Comparable>,
        actual: List<Comparable>,
        typeForMsg: String
    ) {
        val expectedSorted = expected.sortedBy { it.id }
        val expectedIds = expectedSorted.map { it.id }
        val actualSorted = actual.sortedBy { it.id }
        val actualIds = actualSorted.map { it.id }
        diffs.containsExactlyElementsIn(expectedIds, actualIds, "KM_CLASS_${typeForMsg}_AGREE")

        val expectedFiltered = expectedSorted.filter { actualIds.contains(it.id) }
        val actualFiltered = actualSorted.filter { expectedIds.contains(it.id) }
        for ((expectedComparable, actualComparable) in expectedFiltered.zip(actualFiltered)) {
            diffs.withContext(expectedComparable.id) {
                for ((checkName, expectedValue) in expectedComparable.checks) {
                    if (expectedValue is List<*> && expectedValue.isNotEmpty() && expectedValue.first() is Comparable) {
                        @Suppress("UNCHECKED_CAST")
                        compareElements(
                            expectedValue.filterIsInstance<Comparable>(),
                            actualComparable.checks[checkName] as List<Comparable>,
                            "${typeForMsg}_$checkName"
                        )
                    } else {
                        diffs.isEqualTo(expectedValue, actualComparable.checks[checkName], "KM_${typeForMsg}_${checkName}_AGREE")
                    }
                }
            }
        }
    }

    private fun updateSignaturePkg(sig: JvmMethodSignature?): JvmMethodSignature? = sig?.let { it.copy(descriptor = it.descriptor.replace(expectedPkgKm, actualPkgKm)) }

    private fun updateSignaturePkg(sig: JvmFieldSignature?): JvmFieldSignature? = sig?.let { it.copy(descriptor = it.descriptor.replace(expectedPkgKm, actualPkgKm)) }

    private fun updatePkg(s: String): String = s.replace(expectedPkgKm, actualPkgKm)

    private fun kmTypeToString(
        kmType: KmType,
        includeVariance: Boolean = true
    ): String =
        buildString {
            append(updatePkg((kmType.classifier as KmClassifier.Class).name))
            if (kmType.arguments.isNotEmpty()) {
                append("<")
                val args =
                    kmType.arguments.map { arg ->
                        if (arg.variance == null && arg.type == null) return@map "*"
                        if (includeVariance) {
                            val variance =
                                when (arg.variance) {
                                    KmVariance.IN -> "in "
                                    KmVariance.OUT -> "out "
                                    else -> ""
                                }
                            "$variance${kmTypeToString(arg.type!!, includeVariance)}"
                        } else {
                            kmTypeToString(arg.type!!, includeVariance)
                        }
                    }
                append(args.joinToString(", "))
                append(">")
            }
        }

    private inner class Comparable(
        val id: String,
        val checks: Map<String, Any?>
    ) {
        constructor(c: KmConstructor) : this(
            c.signature!!.toString(),
            mapOf(
                "VISIBILITY" to c.visibility,
                "HAS_ANNOTATIONS" to c.hasAnnotations,
                "IS_SECONDARY" to c.isSecondary,
                "HAS_NON_STABLE_PARAM_NAMES" to c.hasNonStableParameterNames,
                "PARAMS" to c.valueParameters.map { Comparable(it) }
            )
        )
        constructor(f: KmFunction) : this(
            f.signature!!.toString(),
            mapOf(
                "KIND" to f.kind,
                "VISIBILITY" to f.visibility,
                "MODALITY" to f.modality,
                "IS_OPERATOR" to f.isOperator,
                "IS_INFIX" to f.isInfix,
                "IS_INLINE" to f.isInline,
                "IS_TAILREC" to f.isTailrec,
                "IS_EXTERNAL" to f.isExternal,
                "IS_SUSPEND" to f.isSuspend,
                "IS_EXPECT" to f.isExpect,
                "HAS_NON_STABLE_PARAM_NAMES" to f.hasNonStableParameterNames,
                "HAS_ANNOTATIONS" to f.hasAnnotations,
                "RETURN_TYPE" to kmTypeToString(f.returnType), // Includes variance, unlike signature
                "PARAMS" to f.valueParameters.map { Comparable(it) }
            )
        )
        constructor(p: KmProperty) : this(
            p.name,
            mapOf(
                "FIELD_SIGNATURE" to p.fieldSignature,
                "GETTER_SIGNATURE" to p.getterSignature,
                "SETTER_SIGNATURE" to p.setterSignature,
                // Property
                "VISIBILITY" to p.visibility,
                "MODALITY" to p.modality,
                "KIND" to p.kind,
                "IS_VAR" to p.isVar,
                "HAS_SETTER" to (p.setter != null),
                "IS_CONST" to p.isConst,
                "IS_LATEINIT" to p.isLateinit,
                "HAS_CONSTANT" to p.hasConstant,
                "IS_EXTERNAL" to p.isExternal,
                "IS_DELEGATED" to p.isDelegated,
                "IS_EXPECT" to p.isExpect,
                "HAS_ANNOTATIONS" to p.hasAnnotations,
                "RETURN_TYPE" to kmTypeToString(p.returnType),
                // Getter
                "GETTER_VISIBILITY" to p.getter.visibility,
                "GETTER_MODALITY" to p.getter.modality,
                "GETTER_IS_NOT_DEFAULT" to p.getter.isNotDefault,
                "GETTER_IS_EXTERNAL" to p.getter.isExternal,
                "GETTER_IS_INLINE" to p.getter.isInline,
                "GETTER_HAS_ANNOTATIONS" to p.getter.hasAnnotations,
                // Setter
                "SETTER_VISIBILITY" to p.setter?.visibility,
                "SETTER_MODALITY" to p.setter?.modality,
                "SETTER_IS_NOT_DEFAULT" to p.setter?.isNotDefault,
                "SETTER_IS_EXTERNAL" to p.setter?.isExternal,
                "SETTER_IS_INLINE" to p.setter?.isInline,
                "SETTER_HAS_ANNOTATIONS" to p.setter?.hasAnnotations,
                "SETTER_PARAMETER" to (p.setterParameter?.let { listOf(Comparable(it)) } ?: emptyList())
            )
        )

        constructor(p: KmValueParameter) : this(
            p.name,
            mapOf(
                "DECLARES_DEFAULT" to p.declaresDefaultValue,
                "IS_CROSS_INLINE" to p.isCrossinline,
                "IS_NO_INLINE" to p.isNoinline,
                // The Kotlin compiler doesn't include value params' variance in the Metadata annotation
                "TYPE" to kmTypeToString(p.type, includeVariance = false)
            )
        )
    }

    companion object {
        private fun parseMetadata(metadata: Metadata): KotlinClassMetadata = KotlinClassMetadata.readStrict(metadata)
    }
}
