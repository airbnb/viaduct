package viaduct.codegen.ct

import javassist.bytecode.AnnotationsAttribute
import javassist.bytecode.ConstPool
import javassist.bytecode.ParameterAnnotationsAttribute
import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmAnnotation
import kotlinx.metadata.KmClass
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmProperty
import kotlinx.metadata.KmType
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.hasAnnotations
import kotlinx.metadata.jvm.JvmMethodSignature
import kotlinx.metadata.jvm.signature
import kotlinx.metadata.kind
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.utils.JavaBinaryName
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.KmName
import viaduct.codegen.utils.refs

sealed class KmWrapper(
    val annotations: Set<Pair<KmAnnotation, Boolean>>
) {
    fun annotationsAttribute(
        cp: ConstPool,
        visible: Boolean,
        notNull: Boolean = false // Set to true if you want an empty annotation attribute to add more things into
    ): AnnotationsAttribute? {
        val annotationsToAdd = annotations.filter { it.second == visible }
        if (annotationsToAdd.isEmpty() && !notNull) return null
        val tag = if (visible) AnnotationsAttribute.visibleTag else AnnotationsAttribute.invisibleTag
        val annotationsAttribute = AnnotationsAttribute(cp, tag)
        for (annotation in annotationsToAdd.map { it.first }) {
            annotationsAttribute.addAnnotation(cp.asCtAnnotation(annotation))
        }
        return annotationsAttribute
    }
}

/** Describes a single class and a hierarchy of nested classes */
class KmClassTree(
    val cls: KmClassWrapper,
    val nested: List<KmClassTree> = emptyList()
) {
    /**
     * Recursively flatten this tree into a flat list of [KmClassWrapper]s.
     *
     * In the flattened result, the `cls` of each tree will precede its nested elements,
     * and nested elements will be flattened depth first.
     */
    fun flatten(): List<KmClassWrapper> = nested.fold(listOf(cls)) { acc, x -> acc + x.flatten() }

    /**
     * Apply a mapping function to a tree, where the mapping function has access to any
     * outer KmClassTree that the current cls might be nested in.
     */
    fun <T> mapWithOuter(fn: (KmClassTree, KmClassTree?) -> T): List<T> {
        fun loop(
            tree: KmClassTree,
            outer: KmClassTree?
        ): List<T> {
            val mappedRoot = fn(tree, outer)
            val mappedNested = tree.nested.flatMap { loop(it, tree) }
            return listOf(mappedRoot) + mappedNested
        }
        return loop(this, null)
    }
}

/**
 * Recursively flatten a collection of [KmClassTree] into a flat list of [KmClassWrapper]s
 * @see KmClassTree.flatten
 */
fun Iterable<KmClassTree>.flatten(): List<KmClassWrapper> = flatMap { it.flatten() }

/**
 * Apply a mapping function to a collection of [KmClassTree]
 * @see KmClassTree.mapWithOuter
 */
fun <T> Iterable<KmClassTree>.mapWithOuter(fn: (KmClassTree, KmClassTree?) -> T): Iterable<T> = flatMap { it.mapWithOuter(fn) }

class KmClassWrapper(
    val kmClass: KmClass,
    val constructors: Iterable<KmConstructorWrapper> = emptyList(),
    val functions: Iterable<KmFunctionWrapper> = emptyList(),
    val properties: Iterable<KmPropertyWrapper> = emptyList(),
    annotations: Set<Pair<KmAnnotation, Boolean>> = emptySet(),
    val tier: Int = 1
) : KmWrapper(annotations) {
    val refs: Set<KmName>

    init {
        if (tier != 0 && tier != 1) {
            throw IllegalArgumentException("Tier must be 0 or 1 {$tier)")
        }
        if (kmClass.kind != ClassKind.CLASS &&
            kmClass.kind != ClassKind.INTERFACE &&
            kmClass.kind != ClassKind.ENUM_CLASS &&
            kmClass.kind != ClassKind.OBJECT
        ) {
            throw IllegalArgumentException("Can only wrap classes, interfaces, enums, and objects (${kmClass.name}: ${kmClass.kind})")
        }
        if (kmClass.kind == ClassKind.INTERFACE) {
            if (constructors.count() != 0) {
                throw IllegalArgumentException("No constructors for interfaces (${kmClass.name}: $constructors)")
            }
            if (properties.count() != 0) {
                throw IllegalArgumentException("No properties for interfaces (${kmClass.name}: $properties)")
            }
        }

        val refSet = mutableSetOf<KmName>()
        kmClass.typeParameters.forEach { refSet.addAll(it.refs) }
        constructors.forEach { refSet.addAll(it.refs) }
        functions.forEach { refSet.addAll(it.refs) }
        properties.forEach { refSet.addAll(it.refs) }
        refSet.addAll(annotations.refs)
        refs = refSet.toSet()
    }

    val asTree: KmClassTree
        get() = KmClassTree(this)
}

class KmConstructorWrapper(
    val constructor: KmConstructor,
    val body: String? = null,
    val superCall: String? = null,
    val defaultParamValues: Map<JavaIdName, String> = emptyMap(),
    annotations: Set<Pair<KmAnnotation, Boolean>> = emptySet(),
    private val visibleParameterAnnotations: Map<JavaIdName, List<KmAnnotation>> = emptyMap(),
    val genSyntheticAccessor: Boolean = false,
) : KmWrapper(annotations) {
    val refs: Set<KmName>

    init {
        if (genSyntheticAccessor) {
            require(constructor.visibility == Visibility.PRIVATE) {
                "synthetic accessors can only be generated for private constructors"
            }
        }
        constructor.also {
            // generate a signature for a KmConstructor if one doesn't already exist
            if (it.signature == null) {
                it.signature = JvmMethodSignature("<init>", constructor.jvmDesc)
            }
            it.hasAnnotations = annotations.isNotEmpty()
        }

        val refSet = mutableSetOf<KmName>()
        refSet.addAll(annotations.refs)
        visibleParameterAnnotations.values.forEach {
            it.forEach {
                refSet.addAll(it.refs)
            }
        }
        refs = refSet.toSet()
    }

    fun visibleParameterAnnotationsAttribute(cp: ConstPool): ParameterAnnotationsAttribute? {
        val annotations =
            constructor.valueParameters
                .map {
                    visibleParameterAnnotations[JavaIdName(it.name)]?.let { annotations ->
                        annotations.map { annotation -> cp.asCtAnnotation(annotation) }.toTypedArray()
                    } ?: emptyArray()
                }.toTypedArray()
        if (annotations.any { it.isNotEmpty() }) {
            return ParameterAnnotationsAttribute(cp, ParameterAnnotationsAttribute.visibleTag).also {
                it.annotations = annotations
            }
        }
        return null
    }
}

/**
 * @param bridgeParameters
 *        On the JVM, implementing a function with generics requires a "synthetic bridge" method.
 *        This method is intended for interop with java and operates on java.lang.Object types that
 *        can be cast to the correct type.
 *        We need to generate a synthetic bridge to be compatible with what kotlinc generates,
 *        even though we expect to not have to call this method.
 *        For example, `setOf(-1)` means that a bridge function will be created that casts
 *        the return value to the expected type.
 *        A use case of the bridgeParameters is a GRT builder's `build` method, which implements
 *        `BuilderBase<T>.build(): T`. It needs to set bridgeParameters to `setOf(-1)` to generate
 *        synthetic bridge.
 */
open class KmFunctionWrapper(
    private val fn: KmFunction,
    val body: String? = null,
    val defaultParamValues: Map<JavaIdName, String> = emptyMap(),
    annotations: Set<Pair<KmAnnotation, Boolean>> = emptySet(),
    val bridgeParameters: Set<Int> = emptySet()
) : KmWrapper(annotations) {
    init {
        val invalidBridgeParams =
            bridgeParameters.filterNot { idx ->
                idx in (-1 until fn.valueParameters.size)
            }
        require(invalidBridgeParams.isEmpty()) {
            "bridgeParameters must be in range of -1 and ${fn.valueParameters.size - 1}, " +
                "found ${bridgeParameters.joinToString(",")}}"
        }
        // non-empty bridgeParameters are an instruction to synthesize a bridge function to `fn`
        // The target of the bridge function can be ambiguous if the bridged function is overridden,
        // so in the interest of simplicity require that the bridged function is final
        if (bridgeParameters.isNotEmpty()) {
            require(fn.modality == Modality.FINAL) {
                "non-empty bridgeParameters are only allowed on final classes"
            }
        }
    }

    val function: KmFunction by lazy {
        fn.also {
            it.signature = JvmMethodSignature(fn.name, jvmDesc)
            it.hasAnnotations = annotations.isNotEmpty()
        }
    }
    val jvmDesc: String get() = jvmMethodDesc(jvmValueParameters, fn.jvmReturnType)

    val refs: Set<KmName> = fn.refs + annotations.refs

    open val jvmValueParameters: List<KmValueParameter> = fn.valueParameters

    fun build(): KmFunction =
        function.also {
            it.hasAnnotations = annotations.isNotEmpty()
        }
}

class KmPropertyWrapper(
    val property: KmProperty,
    val inputType: KmType,
    val getter: KmFunctionWrapper,
    val setter: KmFunctionWrapper?,
    val defaultValue: String?,
    val constructorProperty: Boolean,
    val static: Boolean,
    annotations: Set<Pair<KmAnnotation, Boolean>>,
) : KmWrapper(annotations) {
    val getterName = getter.function.name
    val setterName = setter?.function?.name

    fun hasBackingField() = hasBackingField(getter, setter, static)

    val refs: Set<KmName> = getter.refs + (setter?.refs ?: emptySet()) + annotations.refs

    companion object {
        /**
         * If the getter or setter has a null KmFunctionWrapper.body, or the property is static, a backing field
         * will be created and the default implementation that gets / sets the backing field will be generated.
         * If both the getter and setter (if present) of the property have a custom implementation via a
         * non-null KmFunctionWrapper.body, then a backing field will *not* be generated. This means that
         * accessing the backing field via the `field` identifier
         * (https://kotlinlang.org/docs/properties.html#backing-fields) is currently not supported.
         */
        fun hasBackingField(
            getter: KmFunctionWrapper,
            setter: KmFunctionWrapper?,
            static: Boolean
        ): Boolean {
            if (getter.body == null) return true
            if (setter != null && setter.body == null) return true
            return static
        }
    }
}

/** This class wrapper tells the code-generator that it should
 *  create a class or interface entry for the given name so
 *  code can be compiled against it, but to not actually generate
 *  the class.
 */
class ExternalClassWrapper(
    val name: JavaBinaryName,
    val isInterface: Boolean = false,
    val nested: List<Nested> = emptyList()
) {
    class Nested(
        val nestedName: JavaIdName,
        val flags: Int = Ct.STATIC_PUBLIC_FINAL,
        val nested: List<Nested> = emptyList()
    )
}
