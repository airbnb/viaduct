package viaduct.codegen.utils

import kotlinx.metadata.KmClassifier
import kotlinx.metadata.KmType
import kotlinx.metadata.isNullable

private const val ID = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*"
private const val JAVA_BINARY_NAME_REGEX = "($ID[.])*$ID(\\$$$ID)*"

/** Java and Kotlin identifier names. */
@JvmInline
value class JavaIdName(
    private val name: String
) {
    init {
        if (!checker.matches(name)) {
            throw IllegalArgumentException("Malformed JavaName ($name).")
        }
    }

    override fun toString() = this.name

    companion object {
        private val checker = ID.toRegex()
    }
}

/** Java and Kotlin source-language names.
 *  Dots (.) are used for all separators,
 *  so these are ambiguous when used for
 *  inner classes. */
@JvmInline
value class JavaName(
    private val name: String
) {
    init {
        if (!checker.matches(name)) {
            throw IllegalArgumentException("Malformed JavaName ($name).")
        }
    }

    /** For package and top-level class names only.  Do not use for nested classes. */
    val asKmName: KmName get() = KmName(name.replace('.', '/'))

    override fun toString() = this.name

    companion object {
        private val checker = "($ID[.])*$ID".toRegex()
    }
}

/** Class names that use dots (.) to separate components
 *  of package names, and the package-name from a top-level
 *  class, followed by dollar signs ($) to separate inner-class
 *  names.
 *  See [13.1](https://docs.oracle.com/javase/specs/jls/se8/html/jls-13.html#jls-13.1)
 *  of the Java Language Specification. */
@JvmInline
value class JavaBinaryName(
    private val name: String
) {
    init {
        if (!checker.matches(name)) {
            throw IllegalArgumentException("Malformed JavaBinaryName ($name).")
        }
    }

    val asKmName: KmName get() = KmName(name.replace('.', '/').replace('$', '.'))

    override fun toString() = this.name

    companion object {
        private val checker = JAVA_BINARY_NAME_REGEX.toRegex()
    }
}

/** Class names that use slashes (/) to separate components
 *  of package names, and the package-name from a top-level
 *  class, followed by dots (.) to separate inner-class
 *  names.  (Same as [JavaBinaryName] except using dots in place
 *  of dollar signs.) */
@JvmInline
value class KmName(
    private val name: String
) {
    init {
        if (!checker.matches(name)) {
            throw IllegalArgumentException("Malformed KmName ($name).")
        }
    }

    /** Translates _syntax_, but doesn't do things like kotlin/Any -> java/lang/Object. */
    val asJavaName: JavaName get() = JavaName(name.replace('/', '.'))
    val asJavaBinaryName: JavaBinaryName get() = JavaBinaryName(name.replace('.', '$').replace('/', '.'))

    fun asType(): KmType = KmType().also { it.classifier = KmClassifier.Class(this.name) }

    fun asNullableType(): KmType = asType().also { it.isNullable = true }

    fun append(tail: String) = KmName(name + tail)

    override fun toString() = this.name

    companion object {
        private val checker = "($ID/)*$ID(\\.$ID)*".toRegex()
    }
}
