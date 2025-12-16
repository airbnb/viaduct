package viaduct.codegen.ct

import javassist.ClassPool
import javassist.CtClass
import javassist.bytecode.AccessFlag
import javassist.bytecode.AnnotationsAttribute
import javassist.bytecode.ConstPool
import javassist.bytecode.InnerClassesAttribute
import javassist.bytecode.ParameterAnnotationsAttribute
import javassist.bytecode.SignatureAttribute
import javassist.bytecode.SignatureAttribute.TypeArgument
import javassist.bytecode.annotation.Annotation as CtAnnotation
import javassist.bytecode.annotation.AnnotationMemberValue
import javassist.bytecode.annotation.ArrayMemberValue
import javassist.bytecode.annotation.BooleanMemberValue
import javassist.bytecode.annotation.ByteMemberValue
import javassist.bytecode.annotation.CharMemberValue
import javassist.bytecode.annotation.ClassMemberValue
import javassist.bytecode.annotation.DoubleMemberValue
import javassist.bytecode.annotation.EnumMemberValue
import javassist.bytecode.annotation.FloatMemberValue
import javassist.bytecode.annotation.IntegerMemberValue
import javassist.bytecode.annotation.LongMemberValue
import javassist.bytecode.annotation.MemberValue
import javassist.bytecode.annotation.ShortMemberValue
import javassist.bytecode.annotation.StringMemberValue
import kotlinx.metadata.KmAnnotation
import kotlinx.metadata.KmAnnotationArgument
import kotlinx.metadata.KmClass
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.KmVariance
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.isNullable
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.utils.DEFAULT_IMPLS
import viaduct.codegen.utils.JavaBinaryName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.codegen.utils.name

// Javassist-related utility functions.

fun ConstPool.annotation(jvmFQN: String) = CtAnnotation(jvmFQN, this)

fun ConstPool.annotations(
    viz: Boolean,
    vararg annotations: String
): AnnotationsAttribute {
    val tag = if (viz) AnnotationsAttribute.visibleTag else AnnotationsAttribute.invisibleTag
    val result = AnnotationsAttribute(this, tag)
    annotations.forEach {
        result.addAnnotation(this.annotation(it))
    }
    return result
}

fun ConstPool.annotations(
    viz: Boolean,
    parameterAnnotations: List<List<String>>
): ParameterAnnotationsAttribute {
    val tag = if (viz) ParameterAnnotationsAttribute.visibleTag else ParameterAnnotationsAttribute.invisibleTag
    val paramAnnotations =
        parameterAnnotations
            .map { annotationsOfAParameter ->
                annotationsOfAParameter.map { this.annotation(it) }.toTypedArray()
            }.toTypedArray()
    return ParameterAnnotationsAttribute(this, tag).apply {
        annotations = paramAnnotations
    }
}

fun CtClass.asCtAnnotation(metadata: Metadata): CtAnnotation {
    val cp = classFile.constPool

    // Helper functions
    fun intArrayMemberValue(a: IntArray) = ArrayMemberValue(cp).apply { value = a.map { IntegerMemberValue(cp, it) }.toTypedArray() }

    fun stringArrayMemberValue(a: Array<String>) = ArrayMemberValue(cp).apply { value = a.map { StringMemberValue(it, cp) }.toTypedArray() }

    return cp.annotation("kotlin.Metadata").apply {
        addMemberValue("k", IntegerMemberValue(cp, metadata.kind))
        addMemberValue("mv", intArrayMemberValue(metadata.metadataVersion))
        addMemberValue("d1", stringArrayMemberValue(metadata.data1))
        addMemberValue("d2", stringArrayMemberValue(metadata.data2))

        addMemberValue("xi", IntegerMemberValue(cp, 48))
        // ^^ See https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/jvm/runtime/kotlin/Metadata.kt
        // We are setting:
        //  bit 4: compiled by Kotlin >= 1.4
        //  bit 5: "this class file has stable metadata and ABI"
    }
}

internal val Visibility.jvmAccessFlags get() =
    when (this) {
        Visibility.PRIVATE -> AccessFlag.PRIVATE
        Visibility.PROTECTED -> AccessFlag.PROTECTED
        Visibility.PUBLIC, Visibility.INTERNAL -> AccessFlag.PUBLIC
        else -> throw IllegalArgumentException("Can't handle visibility \"$this\".")
    }

internal val Modality.jvmAccessFlags get() =
    when (this) {
        Modality.FINAL -> AccessFlag.FINAL
        Modality.ABSTRACT -> AccessFlag.ABSTRACT
        Modality.SEALED -> throw IllegalArgumentException("Can't handle sealed modality.")
        else -> 0
    }

internal val KmClass.jvmAccessFlags get() =
    this.visibility.jvmAccessFlags or this.modality.jvmAccessFlags

internal val KmFunction.jvmAccessFlags get() =
    this.visibility.jvmAccessFlags or this.modality.jvmAccessFlags

internal fun ClassPool.getClass(ctName: CtName) = this.get(ctName.toString())

internal fun ConstPool.asCtAnnotation(kmAnnotation: KmAnnotation): CtAnnotation {
    val result = CtAnnotation(KmName(kmAnnotation.className).asJavaBinaryName.toString(), this)
    for ((name, args) in kmAnnotation.arguments.entries) {
        result.addMemberValue(name, asCtMemberValue(args))
    }
    return result
}

private fun ConstPool.asCtMemberValue(arg: KmAnnotationArgument): MemberValue =
    when (arg) {
        is KmAnnotationArgument.ArrayValue -> {
            ArrayMemberValue(this).apply {
                value = arg.elements.map { asCtMemberValue(it) }.toTypedArray()
                // The "type" property of resulting ArrayMemberValue will be null, not sure the implications
            }
        }
        is KmAnnotationArgument.AnnotationValue -> AnnotationMemberValue(asCtAnnotation(arg.annotation), this)
        is KmAnnotationArgument.BooleanValue -> BooleanMemberValue(arg.value, this)
        is KmAnnotationArgument.ByteValue -> ByteMemberValue(arg.value, this)
        is KmAnnotationArgument.CharValue -> CharMemberValue(arg.value, this)
        is KmAnnotationArgument.DoubleValue -> DoubleMemberValue(arg.value, this)
        is KmAnnotationArgument.EnumValue ->
            EnumMemberValue(this).also {
                it.type = KmName(arg.enumClassName).asJavaBinaryName.toString()
                it.value = arg.enumEntryName
            }
        is KmAnnotationArgument.FloatValue -> FloatMemberValue(arg.value, this)
        is KmAnnotationArgument.IntValue -> IntegerMemberValue(this, arg.value)
        is KmAnnotationArgument.LongValue -> LongMemberValue(arg.value, this)
        is KmAnnotationArgument.ArrayKClassValue -> {
            throw IllegalArgumentException("Can't handle KClasses for array-of types yet.")
        }
        is KmAnnotationArgument.KClassValue -> {
            ClassMemberValue(KmName(arg.className).asJavaBinaryName.toString(), this)
        }
        is KmAnnotationArgument.ShortValue -> ShortMemberValue(arg.value, this)
        is KmAnnotationArgument.StringValue -> StringMemberValue(arg.value, this)
        else -> throw IllegalArgumentException("Unexpected annotation argument ($arg).")
    }

/**
 * Returns a ParameterAnnotationsAttribute with @Nullable or @NotNull annotations for each
 * param if applicable. Used for annotating method and constructor parameters.
 * @param paramTypes: the KmType of each value parameter
 */
internal fun nullabilityParamAnnotationsAttribute(
    paramTypes: List<KmType>,
    cp: ConstPool
): ParameterAnnotationsAttribute? {
    val annotations = paramTypes.map { it.nullabilityAnnotation?.let { listOf(it) } ?: emptyList() }
    if (annotations.any { it.isNotEmpty() }) {
        return cp.annotations(false, annotations)
    }
    return null
}

// Km-related utility functions

internal val KmName.asCtName: CtName get() =
    CtName(kmToJvmBoxedName(this).replace('/', '.'))

/** Translates _syntax_, but doesn't do things like kotlin/Any -> java/lang/Object. */
internal val KmName.asJvmName: String get() =
    this.toString().replace('.', '$')

private const val AT_NULLABLE = "org.jetbrains.annotations.Nullable"
private const val AT_NOT_NULL = "org.jetbrains.annotations.NotNull"
internal val KmType.returnNullabilityAnnotation get(): String? {
    if (this.isJavaPrimitive || this.name == Km.UNIT) return null
    return if (isNullable) AT_NULLABLE else AT_NOT_NULL
}

private val KmType.nullabilityAnnotation get(): String? {
    if (this.isJavaPrimitive) return null
    return if (isNullable) AT_NULLABLE else AT_NOT_NULL
}

internal val KmClass.kmName: KmName get() = KmName(name)

internal val KmValueParameter.zeroValueExpression: String get() =
    when (this.javaTypeName.toString()) {
        "boolean" -> "false"
        "double" -> "0.0"
        "int" -> "0"
        "long" -> "0L"
        "short" -> "(short)0"
        else -> "null"
    }

internal val KmFunction.javaReturnTypeName: CtName get() =
    jvmReturnType.javaReturnTypeName

private val KmType.javaReturnTypeName: CtName get() =
    if (name == Km.UNIT) Ct.VOID else javaTypeName

// Helpers for creating JVM signatures

internal val KmFunctionWrapper.jvmSignature: String get() =
    SignatureAttribute
        .MethodSignature(
            null,
            jvmValueParameters.map { it.type.jvmSignature }.toTypedArray(),
            function.jvmReturnType.jvmReturnSignature,
            null
        ).encode()

internal fun KmFunctionWrapper.defaultImplsJvmSignature(defaultImplsClass: CtClass): String =
    SignatureAttribute
        .MethodSignature(
            null,
            defaultImplsJvmValueParameters(defaultImplsClass).map { it.type.jvmSignature }.toTypedArray(),
            function.jvmReturnType.jvmReturnSignature,
            null
        ).encode()

internal val KmConstructor.jvmSignature: String get() =
    SignatureAttribute
        .MethodSignature(
            null,
            valueParameters.map { it.type.jvmSignature }.toTypedArray(),
            SignatureAttribute.BaseType("void"),
            null
        ).encode()

internal val KmPropertyWrapper.fieldJvmSignature: String get() {
    val signatureType = this.inputType.jvmSignature
    return when (signatureType) {
        is SignatureAttribute.BaseType -> signatureType.descriptor.toString()
        is SignatureAttribute.ObjectType -> signatureType.encode()
        else -> throw java.lang.IllegalArgumentException("Unexpected property type jvmSignature")
    }
}

/**
 * If the Kotlin type is represented as a primitive type on the JVM, returns that type,
 * otherwise returns null. Only non-nullable Kotlin types are presented as primitives, since
 * Kotlin boxes nullable types.
 */
internal val KmType.javaPrimitiveName: CtName? get() = // Both
    if (isNullable) {
        null
    } else {
        when (name) {
            Km.BOOLEAN -> Ct.BOOLEAN
            Km.BYTE -> Ct.BYTE
            Km.CHAR -> Ct.CHAR
            Km.DOUBLE -> Ct.DOUBLE
            Km.FLOAT -> Ct.FLOAT
            Km.INT -> Ct.INT
            Km.LONG -> Ct.LONG
            Km.SHORT -> Ct.SHORT
            else -> null
        }
    }

/** Convert a kotlin-metadata namespace name for a type
 *  into the boxed JVM equivalent.  In addition to doing
 *  syntax conversion (eg, `.` -> `$`), it also replaces
 *  Kotlin types with their JVM representatives (eg, kotlin/Any
 *  -> java/lang/Object).
 *
 *  Throws exception for `kotlin/Array` because that type
 *  is translated into the JVM base array type ("["). */
internal fun kmToJvmBoxedName(kmName: KmName): String =
    when (kmName) { // Both
        Km.ANY -> "java/lang/Object"
        Km.ARRAY -> throw IllegalArgumentException("Can't be used on $kmName.")
        Km.BOOLEAN -> "java/lang/Boolean"
        Km.BYTE -> "java/lang/Byte"
        Km.CHAR -> "java/lang/Character"
        Km.DOUBLE -> "java/lang/Double"
        Km.ENUM -> "java/lang/Enum"
        Km.FLOAT -> "java/lang/Float"
        Km.INT -> "java/lang/Integer"
        Km.LIST -> "java/util/List"
        Km.LONG -> "java/lang/Long"
        Km.MAP, Km.MUTABLE_MAP -> "java/util/Map"
        Km.SHORT -> "java/lang/Short"
        Km.STRING -> "java/lang/String"
        Km.UNIT -> kmName.toString() // identity
        else -> kmName.toString().replace('.', '$')
    }

private val KmType.jvmReturnSignature: SignatureAttribute.Type get() =
    if (name == Km.UNIT) {
        SignatureAttribute.BaseType("void")
    } else {
        jvmSignature
    }

private val KmType.jvmSignature: SignatureAttribute.Type get() =
    javaPrimitiveName?.let { SignatureAttribute.BaseType(it.toString()) }
        ?: jvmBoxedSignature

private val KmType.jvmBoxedSignature: SignatureAttribute.Type get() =
    when {
        name == Km.ARRAY -> {
            var dims = 0
            var elementType = this
            do {
                dims += 1
                elementType = elementType.arguments[0].type!!
            } while (elementType.name == Km.ARRAY)
            SignatureAttribute.ArrayType(dims, elementType.jvmBoxedSignature)
        }
        else -> jvmClassType
    }

internal val KmType.jvmClassType: SignatureAttribute.ClassType get() =
    when {
        arguments.size == 0 -> SignatureAttribute.ClassType(kmToJvmBoxedName(name))
        else -> SignatureAttribute.ClassType(kmToJvmBoxedName(name), arguments.jvmTypeArgsSignatures)
    }

internal val List<KmTypeProjection>.jvmTypeArgsSignatures: Array<SignatureAttribute.TypeArgument> get() =
    map {
        if (it == KmTypeProjection.STAR) {
            return@map SignatureAttribute.TypeArgument()
        }
        val arg = it.type!!.jvmBoxedSignature
        if (arg !is SignatureAttribute.ObjectType) {
            throw IllegalArgumentException("Unexpected type-argument ($it).")
        }
        when (it.variance!!) {
            KmVariance.INVARIANT -> SignatureAttribute.TypeArgument(arg)
            KmVariance.OUT -> SignatureAttribute.TypeArgument.subclassOf(arg)
            KmVariance.IN -> SignatureAttribute.TypeArgument.superOf(arg)
        }
    }.toTypedArray()

internal fun KmFunctionWrapper.defaultImplsJvmValueParameters(defaultImplsClass: CtClass): List<KmValueParameter> {
    val interfaceName = defaultImplsClass.name.removeSuffix("$" + DEFAULT_IMPLS)
    val thisParam =
        KmValueParameter("\$this").apply {
            type = JavaBinaryName(interfaceName).asKmName.asType()
        }
    return listOf(thisParam) + jvmValueParameters
}

/** return the InnerClassesAttribute of this class, if one exists */
val CtClass.innerClassesAttribute: InnerClassesAttribute?
    get() =
        if (this.isPrimitive) {
            null
        } else {
            (this.classFile.getAttribute(InnerClassesAttribute.tag)) as InnerClassesAttribute?
        }

/** metadata describing a nested relationship */
internal data class NestEdge(
    /** the nested (ie contained) class */
    val nested: CtClass,
    /** the nesting (ie containing) class */
    val nesting: CtClass,
    /** the name of the nest edge */
    val nestedName: String,
    /** access flags for the nest edge */
    val accessFlags: Int,
) {
    fun write(attr: InnerClassesAttribute) {
        attr.append(nested.name, nesting.name, nestedName, accessFlags)
    }
}

/**
 * A List of [NestEdge] describing all of this class' inner class relationships.
 * This list may include nesting or nested classes that are outside of this class' own
 * nesting class hierarchy.
 */
internal val CtClass.nestEdges: List<NestEdge>
    get() =
        innerClassesAttribute?.let { ica ->
            (0 until ica.tableLength()).mapNotNull { i ->
                val inner = this.classPool.getOrNull(ica.innerClass(i))
                val outer = this.classPool.getOrNull(ica.outerClass(i))
                if (inner != null && outer != null) {
                    NestEdge(inner, outer, ica.innerName(i), ica.accessFlags(i))
                } else {
                    null
                }
            }
        } ?: emptyList()

/** return a [NestEdge] describing this class' relationship to its nesting class, if one exists */
internal val CtClass.nestingEdge: NestEdge?
    get() = nestEdges.firstOrNull { it.nested == this }

/** return a List of [NestEdge] describing this class' relationship to its nested classes */
internal val CtClass.nestedEdges: List<NestEdge>
    get() = nestEdges.filter { it.nesting == this }

/** return a set of all KmName's that this CtClass has a reference to */
internal val CtClass.refs: Set<JavaBinaryName> get() = refClasses.map { JavaBinaryName(it) }.toSet()

internal val CtClass.javaBinaryName: JavaBinaryName get() = JavaBinaryName(this.name)
