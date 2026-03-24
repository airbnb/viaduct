package viaduct.codegen.km

import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmClass
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmVariance
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.isExpect
import kotlinx.metadata.isExternal
import kotlinx.metadata.isFunInterface
import kotlinx.metadata.isInner
import kotlinx.metadata.isValue
import kotlinx.metadata.jvm.JvmMethodSignature
import kotlinx.metadata.jvm.signature
import kotlinx.metadata.kind
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.ct.Ct
import viaduct.codegen.ct.javaTypeName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.codegen.utils.name
import viaduct.invariants.FailureCollector

internal fun checkKmClassInvariants(
    kmOuter: KmClass?,
    kmClass: KmClass,
    check: FailureCollector
) {
    val nestedNames = kmClass.name.split(".")
    if (1 < nestedNames.size) {
        check.isNotNull(kmOuter, "NESTED_CLASSES_NEED_CONTAINER")
        if (kmOuter != null) {
            val outerNames = kmOuter.name.split(".")
            check.containsExactlyElementsIn(outerNames, nestedNames.dropLast(1), "NESTED_CLASS_PROPER_FQN")
            check.contains(nestedNames.last(), kmOuter.nestedClasses, "OUTER_CLASS_KNOWS_NESTED_CLASS")
            check.isEqualTo(ClassKind.CLASS, kmClass.kind, "NESTED_CLASS_RESTRICTION: must be class")
        }
    } else {
        check.isNull(kmOuter, "NON_NESTED_CLASS_HAS_NO_OUTER")
    }

    // Visibility flags
    check.isEqualTo(Visibility.PUBLIC, kmClass.visibility, "CLASSES_ARE_PUBLIC")

    // Modality flags
    check.isNotEqualTo(Modality.SEALED, kmClass.modality, "CLASSES_ARE_NOT_SEALED")
    check.isNotEqualTo(Modality.OPEN, kmClass.modality, "CLASSES_ARE_NOT_OPEN")
    if (kmClass.kind == ClassKind.INTERFACE) {
        check.isEqualTo(Modality.ABSTRACT, kmClass.modality, "INTERFACES_ARE_ABSTRACT")
    } else {
        check.isEqualTo(Modality.FINAL, kmClass.modality, "CLASSES_ARE_FINAL")
    }

    // Class flags
    check.contains(
        kmClass.kind,
        setOf(ClassKind.CLASS, ClassKind.ENUM_CLASS, ClassKind.INTERFACE),
        "CLASS_KIND_RESTRICTION: class, interface, or enum only."
    )
    check.isFalse(kmClass.isInner, "NO_INNER_CLASSES")
    check.isFalse(kmClass.isExternal, "NO_EXTERNAL_CLASSES")
    check.isFalse(kmClass.isExpect, "NO_EXPECT_CLASSES")
    check.isFalse(kmClass.isValue, "NO_VALUE_CLASSES")
    check.isFalse(kmClass.isFunInterface, "NO_FUN_CLASSES")
    check.isEmpty(kmClass.typeParameters as Iterable<*>, "NO_TYPE_PARAMETERS")

    // Note: Invariants on supertypes need to be checked by caller (to resolve names to actual types)

    check.isEmpty(kmClass.typeAliases, "NO_CLASS_TYPE_ALIASES")

    kmClass.constructors.forEach { cons ->
        check.withContext("<init>") {
            checkKmConstructorInvariants(kmClass, cons, check)
        }
    }

    if (0 < kmClass.enumEntries.size || kmClass.kind == ClassKind.ENUM_CLASS) {
        check.isEqualTo(ClassKind.ENUM_CLASS, kmClass.kind, "ONLY_ENUMS_HAVE_VALUES")

        val enumSupertypes = kmClass.supertypes.filter { it.name == Km.ENUM }
        if (check.isEqualTo(
                1,
                enumSupertypes.size,
                "ENUM_SUPERTYPE_RESTRICTION: must implement kotlin/enum (only)."
            )
        ) {
            val enumSupertype = enumSupertypes[0]
            if (check.isEqualTo(
                    1,
                    enumSupertype.arguments.size,
                    "ENUM_SUPERTYPE_RESTRICTION: must have one argument"
                )
            ) {
                val arg = enumSupertype.arguments[0]
                check.isEqualTo(
                    KmVariance.INVARIANT,
                    arg.variance,
                    "ENUM_SUPERTYPE_VARIANCE_RESTRICTION: must be invariant"
                )
                check.isEqualTo(
                    KmName(kmClass.name),
                    arg.type!!.name,
                    "ENUM_SYPERTYPE_PARAM_RESTRICTION: must be of self"
                )
            }
        }
        check.containsNoDuplicates(kmClass.enumEntries, "ENUM_ENTRIES_UNIQUE")

        check.isEqualTo(1, kmClass.constructors.size, "ENUMS_HAVE_ONE_CTOR")
        val cons = kmClass.constructors[0]
        check.isEqualTo(Visibility.PRIVATE, cons.visibility, "ENUM_CTOR_PRIVATE")
        check.isEqualTo(
            JvmMethodSignature("<init>", "(Ljava/lang/String;I)V"),
            cons.signature,
            "ENUM_CTOR_CORRECT_SIGNATURE"
        )
    }

    check.isEmpty(kmClass.sealedSubclasses, "NO_CLASS_SEALEDSUBCLASSES")
    check.isNull(kmClass.inlineClassUnderlyingPropertyName, "NO_INLINE_CLASS_PROPERTY")
    check.isNull(kmClass.inlineClassUnderlyingType, "NO_INLINE_CLASS_TYPE")
}

@Suppress("UNUSED_PARAMETER")
internal fun checkKmConstructorInvariants(
    kmClass: KmClass,
    kmCons: KmConstructor,
    check: FailureCollector
) {
    check.isEqualTo("<init>", kmCons.signature?.name, "CONSTRUCTOR_NAME_IS_INIT")
    val unsupportedParamTypes = listOf(Ct.BYTE, Ct.CHAR, Ct.FLOAT)
    for (param in kmCons.valueParameters) {
        check.isFalse(param.javaTypeName in unsupportedParamTypes, "CONSTRUCTOR_PARAM_UNSUPPORTED_TYPE")
    }
}
