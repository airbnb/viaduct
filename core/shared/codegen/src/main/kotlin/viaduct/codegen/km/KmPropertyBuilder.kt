package viaduct.codegen.km

import kotlinx.metadata.KmAnnotation
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmProperty
import kotlinx.metadata.KmPropertyAccessorAttributes
import kotlinx.metadata.KmType
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.MemberKind
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.hasAnnotations
import kotlinx.metadata.hasConstant
import kotlinx.metadata.isNotDefault
import kotlinx.metadata.isVar
import kotlinx.metadata.jvm.JvmFieldSignature
import kotlinx.metadata.jvm.fieldSignature
import kotlinx.metadata.jvm.getterSignature
import kotlinx.metadata.jvm.setterSignature
import kotlinx.metadata.jvm.signature
import kotlinx.metadata.kind
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.ct.KM_UNIT_TYPE
import viaduct.codegen.ct.KmFunctionWrapper
import viaduct.codegen.ct.KmPropertyWrapper
import viaduct.codegen.ct.fieldJvmDesc
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.name

/**
 * Builds a property with the given attributes. Most of the parameters here are self-explanatory.
 *
 * @param inputType: See the doc entitled `learnings.md` found in the source tree.
 * @param isVariable: True to make a mutable `var` property, false for an immutable `val`.
 * @param constructorProperty: True if this property is declared in the constructor via `val` or `var`
 */
class KmPropertyBuilder(
    private val name: JavaIdName,
    private val type: KmType,
    private val inputType: KmType,
    private val isVariable: Boolean,
    private val constructorProperty: Boolean,
) {
    private var defaultValue: String? = null
    private var getterBody: String? = null
    private var getterVisibility: Visibility = Visibility.PUBLIC
    private var setterBody: String? = null
    private var setterVisibility: Visibility = Visibility.PUBLIC
    private var propertyModality: Modality = Modality.FINAL
    private var hasConstantValue: Boolean = false
    private var static: Boolean = false

    private val fieldAnnotations: MutableSet<Pair<KmAnnotation, Boolean>> = mutableSetOf()
    private val getterAnnotations: MutableSet<Pair<KmAnnotation, Boolean>> = mutableSetOf()
    private val setterAnnotations: MutableSet<Pair<KmAnnotation, Boolean>> = mutableSetOf()

    fun defaultValue(value: String?): KmPropertyBuilder {
        defaultValue = value
        return this
    }

    fun getterBody(body: String?): KmPropertyBuilder {
        getterBody = body
        return this
    }

    fun getterVisibility(visibility: Visibility): KmPropertyBuilder {
        getterVisibility = valid(visibility)
        return this
    }

    fun setterBody(body: String?): KmPropertyBuilder {
        if (!isVariable) {
            throw IllegalStateException("Cannot add setter body for val property")
        }
        setterBody = body
        return this
    }

    fun setterVisibility(visibility: Visibility): KmPropertyBuilder {
        if (!isVariable) {
            throw IllegalStateException("Cannot set setter visibility for val property")
        }
        setterVisibility = valid(visibility)
        return this
    }

    fun addFieldAnnotations(annotations: Set<Pair<KmAnnotation, Boolean>>): KmPropertyBuilder {
        fieldAnnotations.addAll(annotations)
        return this
    }

    fun addGetterAnnotations(annotations: Set<Pair<KmAnnotation, Boolean>>): KmPropertyBuilder {
        getterAnnotations.addAll(annotations)
        return this
    }

    fun addSetterAnnotations(annotations: Set<Pair<KmAnnotation, Boolean>>): KmPropertyBuilder {
        if (!isVariable) {
            throw IllegalStateException("Cannot add setter annotations for val property")
        }
        setterAnnotations.addAll(annotations)
        return this
    }

    fun propertyModality(modality: Modality): KmPropertyBuilder {
        propertyModality = modality
        return this
    }

    /** @see [KmProperty.hasConstant] */
    fun hasConstantValue(hasConstantValue: Boolean): KmPropertyBuilder {
        this.hasConstantValue = hasConstantValue
        return this
    }

    // though there is no "static" modifier in kotlin, we need to ensure that properties of
    // kotlin objects are eventually mapped to static jvm fields.
    // This is used internally by the KmToCt layer but isn't expected to be needed
    // by users of the Km layer
    internal fun static(static: Boolean): KmPropertyBuilder {
        this.static = static
        return this
    }

    private fun valid(visibility: Visibility): Visibility {
        // internal visibility is hard to support because kotlinc will perform name-mangling which is
        // difficult to duplicate
        //  Example:
        //    'getA' gets renamed to 'getA$tools_viaduct_oss_codegen'
        require(visibility != Visibility.INTERNAL) {
            "`internal` visibility is not supported"
        }
        return visibility
    }

    internal fun build(): KmPropertyWrapper {
        inputType.isInputTypeFor(type)?.let {
            throw IllegalArgumentException("${inputType.name} isn't input type for ${type.name} ($it).")
        }

        val property =
            KmProperty(name.toString()).apply {
                returnType = type
                // Property visibility needs to match getter visibility
                visibility = getterVisibility
                modality = propertyModality
                hasAnnotations = fieldAnnotations.isNotEmpty()
                isVar = isVariable
                kind = MemberKind.DECLARATION
                hasConstant = hasConstantValue

                getter.visibility = getterVisibility
                getter.modality = propertyModality
                getter.hasAnnotations = getterAnnotations.isNotEmpty()
                getter.isNotDefault = constructorProperty == false && (getterBody != null || getterAnnotations.isNotEmpty())

                if (isVariable) {
                    // Assigning to [setter] sets [hasSetter]
                    setter =
                        KmPropertyAccessorAttributes().also {
                            it.visibility = setterVisibility
                            it.modality = propertyModality
                            it.hasAnnotations = setterAnnotations.isNotEmpty()
                            it.isNotDefault = constructorProperty == false &&
                                (setterBody != null || setterVisibility != getterVisibility || setterAnnotations.isNotEmpty())
                        }
                    if (setter!!.isNotDefault) {
                        val setterParamName = if (setterBody != null) "value" else "<set-?>"
                        setterParameter = KmValueParameter(setterParamName).also { it.type = inputType }
                    }
                }
            }

        // A note about the getter/setter KmFunction(Wrapper)s we construct here:
        // These are _not_ inserted into the Kotlin-metadata of
        // the generated data class.  Rather, they are used internally
        // by our bytecode-gen functions to allow us to reuse
        // utility functions we have for KmFunction types, e.g.,
        // KmFunction.jvmDesc, KmFunction.signature, and
        // CtMethod.setAdditionalInfoFromKm.
        val getterFn =
            KmFunctionWrapper(
                KmFunction(getterName(name.toString())).apply {
                    visibility = getterVisibility
                    modality = property.getter.modality
                    hasAnnotations = property.getter.hasAnnotations
                    returnType = type
                },
                body = getterBody,
                annotations = getterAnnotations
            )

        // Kotlin var properties with private default setters don't appear in bytecode, but have hasSetter = true:
        //   var foo: String
        //       private set
        val setterFn =
            if ((property.setter != null) && (setterBody != null || setterVisibility != Visibility.PRIVATE)) {
                val setterAttributes = property.setter!!
                KmFunctionWrapper(
                    KmFunction(setterName(name.toString())).apply {
                        visibility = setterAttributes.visibility
                        modality = setterAttributes.modality
                        hasAnnotations = setterAttributes.hasAnnotations
                        returnType = KM_UNIT_TYPE
                        valueParameters.add(
                            KmValueParameter(name).also {
                                it.type = inputType
                            }
                        )
                    },
                    body = setterBody,
                    annotations = setterAnnotations
                )
            } else {
                null
            }

        property.apply {
            if (KmPropertyWrapper.hasBackingField(getterFn, setterFn, static)) {
                fieldSignature = JvmFieldSignature(name, this.fieldJvmDesc)
            }
            if (getterVisibility != Visibility.PRIVATE) {
                getterSignature = getterFn.function.signature // Example of reused utility mentioned above
            }
            setterFn?.function?.signature?.let {
                if (setterVisibility != Visibility.PRIVATE) setterSignature = it
            }
        }

        return KmPropertyWrapper(
            property,
            inputType,
            getterFn,
            setterFn,
            defaultValue,
            constructorProperty,
            static,
            fieldAnnotations,
        )
    }
}
