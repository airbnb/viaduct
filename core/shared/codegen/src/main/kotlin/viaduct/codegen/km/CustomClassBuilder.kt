package viaduct.codegen.km

import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmAnnotation
import kotlinx.metadata.KmClass
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeParameter
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.KmVariance
import kotlinx.metadata.MemberKind
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.hasAnnotations
import kotlinx.metadata.isData
import kotlinx.metadata.isNullable
import kotlinx.metadata.isOperator
import kotlinx.metadata.kind
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.ct.KmClassTree
import viaduct.codegen.ct.KmClassWrapper
import viaduct.codegen.ct.KmConstructorWrapper
import viaduct.codegen.ct.KmFunctionWrapper
import viaduct.codegen.ct.KmPropertyWrapper
import viaduct.codegen.ct.isJavaPrimitive
import viaduct.codegen.ct.javaTypeName
import viaduct.codegen.ct.simpleName
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.codegen.utils.name

/** This class is not meant to be subclassed.  To extend its
 *  functionality, either use delegation as is done in [DataClassBuilder],
 *  or use extension functions as is done in the various XyzGen.kt files.
 */
class CustomClassBuilder internal constructor(
    private val kmKind: ClassKind,
    val kmName: KmName,
    private val classAnnotations: Set<Pair<KmAnnotation, Boolean>> = emptySet(),
    private val isNested: Boolean = false,
    private val isDataClass: Boolean = false,
    private val tier: Int = 1
) : ClassBuilder() {
    val kmType = kmName.asType()

    private val supertypes = mutableListOf<KmType>()
    private val constructors = mutableListOf<KmConstructorWrapper>()
    private val functions = mutableListOf<KmFunctionWrapper>()
    private val properties = mutableListOf<KmPropertyWrapper>()
    private val nestedClasses = mutableListOf<CustomClassBuilder>()
    private val enumEntries = mutableListOf<String>()

    init {
        if (tier != 0 && tier != 1) {
            throw IllegalArgumentException("Only tiers 0 and 1 are supported ($kmName: $tier)")
        }
        if (kmKind == ClassKind.OBJECT) {
            addObjectBoilerplate()
        }
    }

    override fun build(): KmClassTree = buildInternal(null)

    private fun buildInternal(containingClass: KmClassWrapper?): KmClassTree {
        if (isNested && containingClass == null) {
            throw IllegalArgumentException("containingClass expected for nested class $kmName")
        } else if (!isNested && containingClass != null) {
            throw IllegalArgumentException(
                "Unexpected containingClass ${containingClass.kmClass.name} received for non-nested class $kmName"
            )
        }

        val kmClass =
            KmClass().also {
                it.name = kmName.toString()
                it.visibility = Visibility.PUBLIC
                it.modality = if (kmKind == ClassKind.INTERFACE) Modality.ABSTRACT else Modality.FINAL
                it.kind = kmKind
                it.hasAnnotations = classAnnotations.isNotEmpty()
                it.isData = isDataClass
                kmType.arguments.forEachIndexed { i, proj ->
                    val param = KmTypeParameter("T$i", i, proj.variance ?: KmVariance.INVARIANT).also {
                        proj.type?.let { t ->
                            it.upperBounds += t
                        }
                    }
                    it.typeParameters += param
                }
                if (supertypes.isEmpty()) {
                    it.supertypes.add(Km.ANY.asType())
                } else {
                    it.supertypes.addAll(supertypes)
                }
            }

        constructors.forEach {
            kmClass.constructors.add(it.constructor)
        }
        functions.forEach {
            kmClass.functions.add(it.function)
        }
        properties.forEach {
            kmClass.properties.add(it.property)
        }
        if (enumEntries.isNotEmpty()) {
            kmClass.enumEntries.addAll(enumEntries)
        }

        val cls =
            KmClassWrapper(
                kmClass,
                constructors,
                functions,
                properties,
                classAnnotations,
                tier
            )

        val nested =
            nestedClasses
                .map {
                    it.buildInternal(cls).also {
                        cls.kmClass.nestedClasses.add(it.cls.kmClass.simpleName)
                    }
                }
        return KmClassTree(cls, nested)
    }

    private fun addObjectBoilerplate() {
        // add private constructor
        constructors.add(
            KmConstructorWrapper(
                KmConstructor().also {
                    it.visibility = Visibility.PRIVATE
                },
                body = "{}"
            )
        )
    }

    fun addSupertype(kmType: KmType): CustomClassBuilder {
        supertypes.add(kmType)
        return this
    }

    fun addConstructor(
        constructor: KmConstructor,
        superCall: String? = null,
        body: String? = null,
        defaultParamValues: Map<JavaIdName, String> = emptyMap(),
        annotations: Set<Pair<KmAnnotation, Boolean>> = emptySet(),
        visibleParameterAnnotations: Map<JavaIdName, List<KmAnnotation>> = emptyMap(),
        genSyntheticAccessor: Boolean = false
    ): CustomClassBuilder {
        if (kmKind != ClassKind.CLASS && kmKind != ClassKind.ENUM_CLASS) {
            throw IllegalArgumentException("Only classes and enums can have constructors ($kmName: $constructor)")
        }
        constructors.add(
            KmConstructorWrapper(
                constructor,
                body,
                superCall,
                defaultParamValues,
                annotations,
                visibleParameterAnnotations,
                genSyntheticAccessor = genSyntheticAccessor
            )
        )
        return this
    }

    /**
     * @param bridgeParameters
     *   a set of indices that a synthetic bridge method should be generated for,
     *   where 0 describes the first valueParameter, 1 describes the second, etc.
     *   A value of -1 can be used to bridge the function return type
     */
    fun addFunction(
        function: KmFunction,
        body: String? = null,
        defaultParamValues: Map<JavaIdName, String> = emptyMap(),
        annotations: Set<Pair<KmAnnotation, Boolean>> = emptySet(),
        bridgeParameters: Set<Int> = emptySet()
    ): CustomClassBuilder {
        functions.add(
            KmFunctionWrapper(
                function,
                body,
                defaultParamValues,
                annotations,
                bridgeParameters
            )
        )
        return this
    }

    fun addSuspendFunction(
        function: KmFunction,
        returnTypeAsInputForSuspend: KmType,
        body: String? = null,
        defaultParamValues: Map<JavaIdName, String> = emptyMap(),
        annotations: Set<Pair<KmAnnotation, Boolean>> = emptySet()
    ): CustomClassBuilder {
        functions.add(
            KmSuspendFunctionWrapper(function, returnTypeAsInputForSuspend, body, defaultParamValues, annotations)
        )
        return this
    }

    fun addProperty(builder: KmPropertyBuilder): CustomClassBuilder {
        addPropertyInternal(builder)
        return this
    }

    fun addEnumEntry(vararg entry: String): CustomClassBuilder {
        enumEntries.addAll(entry)
        return this
    }

    internal fun addPropertyInternal(builder: KmPropertyBuilder): KmPropertyWrapper {
        if (kmKind == ClassKind.OBJECT) {
            builder.static(true)
        }
        val prop = builder.build()
        if (kmKind != ClassKind.CLASS && kmKind != ClassKind.OBJECT) {
            throw IllegalArgumentException(
                "Only classes and objects can have properties ($kmName: ${prop.property})"
            )
        }
        properties.add(prop)
        return prop
    }

    /** To simplify testing, we only support public final non-inner classes */
    fun nestedClassBuilder(
        simpleName: JavaIdName,
        annotations: Set<Pair<KmAnnotation, Boolean>> = emptySet(),
        kind: ClassKind = ClassKind.CLASS
    ): CustomClassBuilder {
        val nestedName = KmName("${this.kmName}.$simpleName")
        if (nestedClasses.firstOrNull { it.kmName == nestedName } != null) {
            throw IllegalArgumentException("Duplicate nested class: $nestedName")
        }
        return CustomClassBuilder(kind, nestedName, annotations, isNested = true).also {
            nestedClasses.add(it)
        }
    }

    /**
     * Adds the copy() function that's in Kotlin data classes. This can also be used for pseudo-data classes
     * that have a getter for every constructor param.
     *
     * For the following data class:
     *     data class MyClass(val a: String, val b: Int)
     * The copy(String a, int b) method body is:
     *     Intrinsics.checkNotNullParameter((Object)a, "a")
     *     return new MyClass(a, b)
     *
     * The copy$default method will use the constructor parameter getters as the default values.
     *
     * @param constructorValueParams: The copy method must have the same parameters as the primary constructor
     * @param isSynthesized: Set to false for pseudo-data classes that explicitly declare this function
     */
    fun addCopyFun(
        constructorValueParams: List<KmValueParameter>,
        isSynthesized: Boolean = true
    ): CustomClassBuilder {
        val copyFn =
            KmFunction("copy").apply {
                visibility = Visibility.PUBLIC
                modality = Modality.FINAL
                valueParameters.addAll(constructorValueParams)
                returnType = kmName.asType()
                if (isSynthesized) kind = MemberKind.SYNTHESIZED
            }

        val defaultParamValues =
            constructorValueParams.associate {
                JavaIdName(it.name) to "$1.${getterName(it.name)}()"
            }

        val body =
            buildString {
                val javaName = kmName.asJavaName
                append("{")
                constructorValueParams.forEachIndexed { index, param ->
                    checkNotNullParameterExpression(param.type, index + 1, param.name)?.let {
                        append(it)
                    }
                }
                append("return new $javaName($$);")
                append("}")
            }

        this.addFunction(copyFn, body, defaultParamValues)
        return this
    }

    /**
     * Adds the equals() function that's in Kotlin data classes. This can also be used for pseudo-data classes
     * given a list of properties to compare.
     *
     * For the following data class:
     *     data class MyClass(val a: String, val b: Int)
     *
     * The equals(Object other) body is:
     *     if (this == other) return true;
     *     if (!(other instanceof MyClass)) return false;
     *     MyClass o = (MyClass)other;
     *     return kotlin.jvm.internal.Intrinsics.areEqual((java.lang.Object)this.a), (java.lang.Object)o.getA()) &&
     *         this.b == o.getB();
     */
    fun addEqualsFun(
        properties: List<KmPropertyBuilder>,
        isSynthesized: Boolean = true
    ): CustomClassBuilder {
        addEqualsFun(properties.map { it.build() }, isSynthesized)
        return this
    }

    internal fun addEqualsFun(
        properties: List<KmPropertyWrapper>,
        isSynthesized: Boolean = true
    ): CustomClassBuilder {
        val equalsFn =
            KmFunction("equals").apply {
                visibility = Visibility.PUBLIC
                modality = Modality.OPEN
                valueParameters.add(
                    KmValueParameter("other").apply {
                        type = Km.ANY.asNullableType()
                    }
                )
                returnType = Km.BOOLEAN.asType()
                isOperator = true
                if (isSynthesized) kind = MemberKind.SYNTHESIZED
            }

        val body =
            run {
                val javaName = kmName.asJavaName
                val otherCastName = "o"
                val propertiesEqualsChecks =
                    properties.map { propWrapper ->
                        val gn = propWrapper.getterName
                        if (propWrapper.property.returnType.isJavaPrimitive) {
                            "this.$gn() == $otherCastName.$gn()"
                        } else {
                            val eq = "kotlin.jvm.internal.Intrinsics.areEqual"
                            "$eq((java.lang.Object)this.$gn(), (java.lang.Object)$otherCastName.$gn())"
                        }
                    }
                val result = when (properties.size) {
                    0 -> "true"
                    else -> propertiesEqualsChecks.joinToString(" && ")
                }
                """
                {
                    if (this == $1) return true;
                    if (!($1 instanceof $javaName)) return false;
                    $javaName $otherCastName = ($javaName)$1;
                    return $result;
                }
                """.trimIndent()
            }

        this.addFunction(equalsFn, body)
        return this
    }

    /**
     * Adds the hashcode() function that's in Kotlin data classes. This can also be used for pseudo-data classes
     * given a list of properties that should be used to compute the hashcode.
     *
     * For the following data class:
     *      data class MyClass(val a: String, val b: Int, val c: Char?)
     *
     * The hashCode() method body is:
     *      int result = 0;
     *      result = result * 31 + this.a.hashCode();
     *      result = result * 31 + Integer.hashCode(this.b);
     *      result = result * 31 + (c == null ? 0 : c.hashCode());
     *      return result;
     */
    fun addHashcodeFun(
        properties: List<KmPropertyBuilder>,
        isSynthesized: Boolean = true
    ): CustomClassBuilder {
        addHashcodeFun(properties.map { it.build() }, isSynthesized)
        return this
    }

    internal fun addHashcodeFun(
        properties: List<KmPropertyWrapper>,
        isSynthesized: Boolean = true
    ): CustomClassBuilder {
        val hashCodeFn =
            KmFunction("hashCode").apply {
                visibility = Visibility.PUBLIC
                modality = Modality.OPEN
                returnType = Km.INT.asType()
                if (isSynthesized) kind = MemberKind.SYNTHESIZED
            }

        // Javassist code for expression that returns the hashcode
        // of a property
        fun hashCodeExpression(propWrapper: KmPropertyWrapper): String =
            run {
                val propertyRef = "this.${propWrapper.getterName}()"
                val expr =
                    when (propWrapper.property.javaTypeName.toString()) {
                        "boolean" -> "($propertyRef ? 1 : 0)"
                        "byte" -> "java.lang.Byte.hashCode($propertyRef)"
                        "char" -> "java.lang.Character.hashCode($propertyRef)"
                        "double" -> "java.lang.Double.hashCode($propertyRef)"
                        "float" -> "java.lang.Float.hashCode($propertyRef)"
                        "int" -> "java.lang.Integer.hashCode($propertyRef)"
                        "long" -> "java.lang.Long.hashCode($propertyRef)"
                        "short" -> "java.lang.Short.hashCode($propertyRef)"
                        else -> {
                            if (propWrapper.property.returnType.name == Km.ARRAY) {
                                "java.util.Arrays.hashCode($propertyRef)"
                            } else {
                                "$propertyRef.hashCode()"
                            }
                        }
                    }
                if (propWrapper.property.returnType.isNullable) {
                    "($propertyRef == null ? 0 : $expr)"
                } else {
                    expr
                }
            }

        val body =
            buildString {
                append("{ int result = 0; ")
                properties.forEach {
                    append("result = result * 31 + ${hashCodeExpression(it)}; ")
                }
                append("return result; }")
            }

        this.addFunction(hashCodeFn, body)
        return this
    }
}

/**
 * Use this instead of KmFunctionWrapper for suspend functions.
 *
 * @param returnTypeAsInputForSuspend: Suspend functions compile to methods with an additional
 *        Continuation<T> parameter where T is the same as function.returnType, but with
 *        potentially different variance. See the BridgeSchema.TypeExpr.kmType extension function for more
 *        information.
 */
private class KmSuspendFunctionWrapper(
    fn: KmFunction,
    private val returnTypeAsInputForSuspend: KmType,
    body: String? = null,
    defaultParamValues: Map<JavaIdName, String> = emptyMap(),
    annotations: Set<Pair<KmAnnotation, Boolean>> = emptySet()
) : KmFunctionWrapper(fn, body, defaultParamValues, annotations) {
    override val jvmValueParameters: List<KmValueParameter> =
        run {
            val continuation =
                KmValueParameter("__c").apply {
                    type =
                        KmName("kotlin/coroutines/Continuation").asType().apply {
                            var variance =
                                // Kotlin special-cases "Any" because it has no superclass
                                if (returnTypeAsInputForSuspend.name == Km.ANY) {
                                    KmVariance.INVARIANT
                                } else {
                                    KmVariance.IN
                                }
                            arguments.add(KmTypeProjection(variance, returnTypeAsInputForSuspend))
                        }
                }
            fn.valueParameters + continuation
        }
}
