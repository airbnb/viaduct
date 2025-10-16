package viaduct.codegen.km.ctdiff

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import javassist.ClassPool
import javassist.CtBehavior
import javassist.CtClass
import javassist.CtConstructor
import javassist.bytecode.AnnotationsAttribute
import javassist.bytecode.AttributeInfo
import javassist.bytecode.InnerClassesAttribute
import javassist.bytecode.ParameterAnnotationsAttribute
import viaduct.invariants.InvariantChecker

/**
 * Compares two classes, checking that they have the same structure. This currently uses a mix of reflection and
 * Javassist, since we can't access RuntimeInvisibleAnnotations via reflection.
 */
class ClassDiff(
    /** Cannot be a prefix of [actualPkg]! */
    val expectedPkg: String,
    val actualPkg: String,
    val diffs: InvariantChecker = InvariantChecker(),
    private val javassistPool: ClassPool = ClassPool.getDefault()
) {
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

            // Check class-level annotations using Javassist since that also includes runtime invisible annotations
            // Skip Metadata ones
            val expectedCtClass = javassistPool.get(expected.name)
            val actualCtClass = javassistPool.get(actual.name)
            val expClassAnnotations = expectedCtClass.classFile.attributes.comparableAnnotations()
            val actClassAnnotations = actualCtClass.classFile2.attributes.comparableAnnotations()
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
                        get(): List<String> {
                            val ctClass = javassistPool.getCtClass(el.declaringClass.name)
                            val ctField = ctClass.declaredFields.first { it.name == el.name }
                            return ctField.fieldInfo2.attributes.comparableAnnotations()
                        }
                    override val identifier get() = el.name

                    override fun elSpecificComparisons(actualEl: Element<Field>) {
                        diffs.typeNamesAreEqual(el.genericType, actualEl.self.genericType, "FIELD_TYPES_AGREE")
                    }
                }
            }

            // Compare methods and constructors
            class ExecutableElement<T : CtBehavior>(override val self: T) : Element<T> {
                override val identifier get() = self.sig
                override val modifiers get() = self.modifiers
                override val annotations get() = self.methodInfo2.attributes.comparableAnnotations()

                override fun elSpecificComparisons(actualEl: Element<T>) {
                    // Because the entire method signature is encoded into [identifier]
                    // the check that expected and actual have the same set of executable
                    // identifiers is implicitly checking that their signatures agree.
                }
            }
            compareElements(expectedCtClass.methodsToCompare, actualCtClass.methodsToCompare, "METHOD") {
                ExecutableElement(it)
            }

            // DefaultImpls generated by the Kotlin compiler don't have constructors, whereas Javassist inserts
            // an empty constructor for actual
            if (!expected.name.endsWith("\$DefaultImpls")) {
                compareElements(
                    expectedCtClass.declaredConstructors,
                    actualCtClass.declaredConstructors,
                    "CTOR"
                ) { ExecutableElement(it) }
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
                    diffs.containsExactlyElementsIn(expEl.annotations, actEl.annotations, "${elName}_ANNOTATIONS_AGREE")
                }
            }
            expEl.elSpecificComparisons(actEl)
        }
    }

    /** Return the "generic signature" of the method stripped of any
     *  modifiers and also replacing [expectedPkg] with [actualPkg].
     *  The result is a string that uniquely identifies a method in
     *  the face of overloading and in the face of the package-renaming
     *  that allows us to compare across packages.
     */
    private val CtBehavior.sig: String
        get() {
            val asString =
                when {
                    // Kotlin compiler (like javac, see:
                    // See https://stackoverflow.com/questions/32829143/enum-disassembled-with-javap-doesnt-show-constructor-arguments)
                    // does not generate signature attributes for enum constructors
                    this is CtConstructor && this.declaringClass.isEnum -> "${this.name} ${this.methodInfo2.descriptor}"
                    else -> "${this.name} ${this.genericSignature ?: this.methodInfo2.descriptor}"
                }
            return asString.replace(expectedPkgSig, actualPkgSig)
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

    /**
     * Converts the Javassist AttributeInfo list to a list of all annotations contained in those attributes.
     * Filters out the kotlin.Metadata annotation since that's checked separately via [KmMetadataDiff].
     * Appends the attribute name (e.g. RuntimeInvisibleAnnotations) to the annotation string so we don't lose
     * context about which attribute the annotation belongs to.
     */
    private fun List<AttributeInfo>.comparableAnnotations(): List<String> {
        return this.flatMap { attribute ->
            if (attribute is AnnotationsAttribute) {
                attribute.annotations
                    .filter { it.typeName != "kotlin.Metadata" }
                    .map { "${attribute.name}:${it.toString().packageNormalized}" }
            } else if (attribute is ParameterAnnotationsAttribute) {
                attribute.annotations.flatMapIndexed { index, paramAnnotations ->
                    paramAnnotations.map { annotation ->
                        "${attribute.name}[$index]:${annotation.toString().packageNormalized}"
                    }
                }
            } else if (attribute is InnerClassesAttribute) {
                val list = (0 until attribute.tableLength()).map { i ->
                    listOf(
                        "attr=${attribute.name}",
                        "outer=${attribute.outerClass(i)?.packageNormalized ?: "<null>"}",
                        "inner=${attribute.innerClass(i)?.packageNormalized ?: "<null>"}",
                        "name=${attribute.innerName(i)}",
                        "flags=${attribute.accessFlags(i)}"
                    ).joinToString("  ")
                }
                list.sorted()
            } else {
                emptyList()
            }
        }
    }

    private val String.packageNormalized: String
        get() = replace(expectedPkg, actualPkg)
}

// JaCoCo, which we use for the coverage job in CI, will insert fields and methods
val Class<*>.fieldsToCompare
    get() = this.declaredFields.filter {
        it.name != "this\$0" && !it.name.startsWith("\$jacoco")
    }.toTypedArray()

val Class<*>.methodsToCompare
    get() = this.declaredMethods.filterNot { it.name.startsWith("\$jacoco") }.toTypedArray()

val CtClass.methodsToCompare
    get() = this.declaredMethods.filterNot { it.name.startsWith("\$jacoco") }.toTypedArray()
