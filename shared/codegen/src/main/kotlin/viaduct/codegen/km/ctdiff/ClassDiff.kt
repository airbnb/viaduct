package viaduct.codegen.km.ctdiff

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import javassist.ClassPool
import viaduct.invariants.InvariantChecker

/**
 * Compares two classes, checking that they have the same structure.
 * Uses ClassFinder abstraction for class inspection, enabling support for
 * both Javassist-based (Kotlin codegen) and reflection-based (Java codegen) class loading.
 */
class ClassDiff(
    /** Cannot be a prefix of [actualPkg]! */
    val expectedPkg: String,
    val actualPkg: String,
    val diffs: InvariantChecker = InvariantChecker(),
    private val classFinder: ClassFinder = JavassistClassFinder()
) {
    /**
     * Secondary constructor for backwards compatibility with existing code that passes ClassPool directly.
     */
    constructor(
        expectedPkg: String,
        actualPkg: String,
        diffs: InvariantChecker,
        javassistPool: ClassPool
    ) : this(
        expectedPkg,
        actualPkg,
        diffs,
        JavassistClassFinder(javassistPool, ClassLoader.getSystemClassLoader())
    )

    /**
     * Constructor for backwards compatibility with code that only passes package names and ClassPool.
     */
    constructor(
        expectedPkg: String,
        actualPkg: String,
        javassistPool: ClassPool
    ) : this(
        expectedPkg,
        actualPkg,
        InvariantChecker(),
        JavassistClassFinder(javassistPool, ClassLoader.getSystemClassLoader())
    )

    internal var elementsTested = 0
        private set

    private val kmMetadataDiff = KmMetadataDiff(expectedPkg, actualPkg, diffs)

    private val expectedPkgSig = expectedPkg.replace('.', '/')
    private val actualPkgSig = actualPkg.replace('.', '/')

    private fun kindCheck(
        expected: Class<*>,
        actual: Class<*>,
        kind: String,
        pred: (Class<*>) -> Boolean
    ) {
        if (pred(expected) || pred(actual)) {
            throw IllegalArgumentException("Can't be called on $kind types (${pred(expected)}, ${pred(actual)}")
        }
    }

    /** Compares the "structure" of two class files, including annotations
     *  and signatures, but doesn't attempt to compare method bodies.
     *  Differences are logged to [diffs].  To
     *  simplify the setup of test fixtures, we want to allow the actual set
     *  of classes to live in one package and the expected set in a different
     *  one (so we can load both without needed ClassLoader tricks).  Thus,
     *  this function does not compare the packages of the two classes, and when
     *  comparing fully-qualified names, occurrences of [actualPkg] will be
     *  replaced with [expectedPkg] for comparison purposes. */
    fun compare(
        expected: Class<*>,
        actual: Class<*>
    ): Unit =
        diffs.withContext(expected.simpleName) {
            kindCheck(expected, actual, "annotation", Class<*>::isAnnotation)
            kindCheck(expected, actual, "anonymous", Class<*>::isAnonymousClass)
            kindCheck(expected, actual, "array", Class<*>::isArray)
            kindCheck(expected, actual, "local", Class<*>::isLocalClass)
            kindCheck(expected, actual, "primitive", Class<*>::isPrimitive)
            elementsTested++

            diffs.modifiersAreSame(expected.modifiers, actual.modifiers, "CLASS_MODIFIERS_AGREE")

            // Check class-level annotations using ClassFinder (which may use Javassist for runtime invisible annotations)
            // Skip Metadata ones. Normalize both to handle annotation classes from shared packages.
            val expClassAnnotations = classFinder.getClassAnnotations(expected).map { it.packageNormalized }
            val actClassAnnotations = classFinder.getClassAnnotations(actual).map { it.packageNormalized }
            diffs.containsExactlyElementsIn(expClassAnnotations, actClassAnnotations, "CLASS_ANNOTATIONS_AGREE")

            // Compare Metadata
            kmMetadataDiff.compare(expected, actual)

            diffs.isEqualTo(
                expected.declaringClass?.name?.packageNormalized,
                actual.declaringClass?.name,
                "DECLARING_CLASS_AGREES"
            )

            diffs.isEqualTo(
                expected.genericSuperclass?.typeName?.packageNormalized,
                actual.genericSuperclass?.typeName,
                "CLASS_SUPERCLASS_AGREES"
            )

            diffs.containsExactlyElementsIn(
                expected.genericInterfaces.map { it.typeName.packageNormalized },
                actual.genericInterfaces.map { it.typeName },
                "CLASS_SUPER_INTERFACES_AGREE"
            )

            if (expected.enumConstants == null) {
                diffs.isNull(actual.enumConstants, "ENUM_CONSTANTS_BOTH_NULL")
            } else {
                diffs.containsExactlyElementsIn(
                    expected.enumConstants!!.map { it.toString() },
                    actual.enumConstants!!.map { it.toString() },
                    "ENUM_CONSTANTS_AGREE"
                )
            }

            // Check fields
            compareElements(expected.fieldsToCompare, actual.fieldsToCompare, "FIELD") { el ->
                object : Element<Field> {
                    override val self get() = el
                    override val modifiers get() = el.modifiers
                    override val annotations
                        get(): List<String> = classFinder.getFieldAnnotations(el.declaringClass, el.name)
                    override val identifier get() = el.name

                    override fun elSpecificComparisons(actualEl: Element<Field>) {
                        diffs.typeNamesAreEqual(el.genericType, actualEl.self.genericType, "FIELD_TYPES_AGREE")
                    }
                }
            }

            // Compare methods using ClassFinder
            compareMethodInfos(
                classFinder.getMethodSignatures(expected),
                classFinder.getMethodSignatures(actual),
                "METHOD"
            )

            // DefaultImpls generated by the Kotlin compiler don't have constructors, whereas Javassist inserts
            // an empty constructor for actual
            if (!expected.name.endsWith("\$DefaultImpls")) {
                compareConstructorInfos(
                    classFinder.getConstructorSignatures(expected),
                    classFinder.getConstructorSignatures(actual),
                    "CTOR"
                )
            }

            // Compare nested classes
            compareElements(expected.declaredClasses, actual.declaredClasses, "NESTED_CLASS") { clazz ->
                object : Element<Class<*>> {
                    override val self get() = clazz
                    override val identifier get() = self.name.packageNormalized

                    // modifiers and annotations are checked in elSpecificComparisons
                    override val modifiers = 0
                    override val annotations = emptyList<String>()

                    override fun elSpecificComparisons(actualEl: Element<Class<*>>) {
                        this@ClassDiff.compare(self, actualEl.self)
                    }
                }
            }
        }

    private interface Element<T> {
        val self: T
        val identifier: String
        val modifiers: Int
        val annotations: List<String>

        fun elSpecificComparisons(actualEl: Element<T>)
    }

    private fun <T> compareElements(
        expected: Array<T>,
        actual: Array<T>,
        elName: String,
        factory: (T) -> Element<T>
    ) {
        val expSorted = expected.map { factory(it) }.sortedBy { it.identifier }
        val expIds = expSorted.map { it.identifier }
        val actSorted = actual.map { factory(it) }.sortedBy { it.identifier }
        val actIds = actSorted.map { it.identifier }
        diffs.containsExactlyElementsIn(expIds, actIds, "CLASS_${elName}S_AGREE")
        val expFiltered = expSorted.filter { actIds.contains(it.identifier) }
        val actFiltered = actSorted.filter { expIds.contains(it.identifier) }
        if (!diffs.containsExactlyElementsIn(
                expFiltered.map { it.identifier },
                actFiltered.map { it.identifier },
                "${elName}_SHOULD_NOT_HAPPEN"
            )
        ) {
            return
        }
        for ((expEl, actEl) in expFiltered.zip(actFiltered)) {
            if (expEl.self !is Class<*> && actEl.self !is Class<*>) {
                diffs.withContext(expEl.identifier) {
                    // For classes, elSpecificComparisons recursively calls ClassDiff.compare,
                    // which checks mods and annotations and increments elementsTested
                    elementsTested++
                    diffs.modifiersAreSame(expEl.modifiers, actEl.modifiers, "${elName}_MODIFIERS_AGREE")
                    // Normalize both to handle annotation classes from shared packages
                    diffs.containsExactlyElementsIn(
                        expEl.annotations.map { it.packageNormalized },
                        actEl.annotations.map { it.packageNormalized },
                        "${elName}_ANNOTATIONS_AGREE"
                    )
                }
            }
            expEl.elSpecificComparisons(actEl)
        }
    }

    /**
     * Compare method signatures from ClassFinder.
     * Handles package normalization for both expected and actual signatures.
     */
    private fun compareMethodInfos(
        expected: List<MethodInfo>,
        actual: List<MethodInfo>,
        elName: String
    ) {
        val expSorted = expected.sortedBy { it.signature.packageNormalized }
        val expIds = expSorted.map { it.signature.packageNormalized }
        val actSorted = actual.sortedBy { it.signature.packageNormalized }
        val actIds = actSorted.map { it.signature.packageNormalized }

        diffs.containsExactlyElementsIn(expIds, actIds, "CLASS_${elName}S_AGREE")

        val expFiltered = expSorted.filter { actIds.contains(it.signature.packageNormalized) }
        val actFiltered = actSorted.filter { expIds.contains(it.signature.packageNormalized) }

        if (!diffs.containsExactlyElementsIn(
                expFiltered.map { it.signature.packageNormalized },
                actFiltered.map { it.signature.packageNormalized },
                "${elName}_SHOULD_NOT_HAPPEN"
            )
        ) {
            return
        }

        for ((expInfo, actInfo) in expFiltered.zip(actFiltered)) {
            diffs.withContext(expInfo.signature.packageNormalized) {
                elementsTested++
                diffs.modifiersAreSame(expInfo.modifiers, actInfo.modifiers, "${elName}_MODIFIERS_AGREE")
                diffs.containsExactlyElementsIn(
                    expInfo.annotations.map { it.packageNormalized },
                    actInfo.annotations.map { it.packageNormalized },
                    "${elName}_ANNOTATIONS_AGREE"
                )
            }
        }
    }

    /**
     * Compare constructor signatures from ClassFinder.
     * Handles package normalization for both expected and actual signatures.
     */
    private fun compareConstructorInfos(
        expected: List<ConstructorInfo>,
        actual: List<ConstructorInfo>,
        elName: String
    ) {
        val expSorted = expected.sortedBy { it.signature.packageNormalized }
        val expIds = expSorted.map { it.signature.packageNormalized }
        val actSorted = actual.sortedBy { it.signature.packageNormalized }
        val actIds = actSorted.map { it.signature.packageNormalized }

        diffs.containsExactlyElementsIn(expIds, actIds, "CLASS_${elName}S_AGREE")

        val expFiltered = expSorted.filter { actIds.contains(it.signature.packageNormalized) }
        val actFiltered = actSorted.filter { expIds.contains(it.signature.packageNormalized) }

        if (!diffs.containsExactlyElementsIn(
                expFiltered.map { it.signature.packageNormalized },
                actFiltered.map { it.signature.packageNormalized },
                "${elName}_SHOULD_NOT_HAPPEN"
            )
        ) {
            return
        }

        for ((expInfo, actInfo) in expFiltered.zip(actFiltered)) {
            diffs.withContext(expInfo.signature.packageNormalized) {
                elementsTested++
                diffs.modifiersAreSame(expInfo.modifiers, actInfo.modifiers, "${elName}_MODIFIERS_AGREE")
                diffs.containsExactlyElementsIn(
                    expInfo.annotations.map { it.packageNormalized },
                    actInfo.annotations.map { it.packageNormalized },
                    "${elName}_ANNOTATIONS_AGREE"
                )
            }
        }
    }

    private fun InvariantChecker.typeNamesAreEqual(
        expected: Type,
        actual: Type,
        msg: String
    ) {
        val expTypeName = expected.typeName.packageNormalized
        this.isEqualTo(expTypeName, actual.typeName, msg)
    }

    private fun InvariantChecker.modifiersAreSame(
        expectedModifiers: Int,
        actualModifiers: Int,
        msg: String
    ) {
        this.isEqualTo(Modifier.toString(expectedModifiers), Modifier.toString(actualModifiers), msg)
    }

    private val String.packageNormalized: String
        get() = replace(expectedPkg, actualPkg)
            .replace(expectedPkgSig, actualPkgSig)
}

// JaCoCo, which we use for the coverage job in CI, will insert fields and methods
val Class<*>.fieldsToCompare
    get() = this.declaredFields
        .filter {
            it.name != "this\$0" && !it.name.startsWith("\$jacoco")
        }.toTypedArray()

val Class<*>.methodsToCompare
    get() = this.declaredMethods.filterNot { it.name.startsWith("\$jacoco") }.toTypedArray()
