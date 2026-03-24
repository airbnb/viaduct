package viaduct.codegen.ct

/** Similar to JavaBinaryName, except that it allows "[]"
 *  at the end of a JavaBinaryName to indicate arrays
 *  (of arbitrary depth).  Used by Javassist (in particular,
 *  by ClassPool.get, for example, where getting a CtClass
 *  that represents an array is useful). */
@JvmInline
value class CtName(
    private val name: String
) {
    init {
        if (!checker.matches(name)) {
            throw IllegalArgumentException("Malformed CtName ($name).")
        }
    }

    override fun toString() = name.toString()

    companion object {
        private val ID = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*"
        private val JAVA_BINARY_NAME_REGEX = "($ID[.])*$ID(\\$$$ID)*"
        private val checker = Regex("$JAVA_BINARY_NAME_REGEX(\\[\\])*")
    }
}
