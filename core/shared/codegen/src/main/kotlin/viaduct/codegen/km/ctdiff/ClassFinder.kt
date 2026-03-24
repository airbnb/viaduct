package viaduct.codegen.km.ctdiff

/**
 * Abstraction for finding and inspecting classes.
 * Enables ClassDiff to work with classes from different sources without
 * depending on Javassist directly.
 *
 * This interface has NO Javassist dependency - it uses only standard Java types.
 */
interface ClassFinder {
    /**
     * Find a class by its fully-qualified name.
     * @return the Java Class object
     * @throws ClassNotFoundException if the class cannot be found
     */
    fun find(className: String): Class<*>

    /**
     * Get class-level annotations as comparable strings.
     * Includes both RuntimeVisible and RuntimeInvisible annotations (depending on implementation).
     * Excludes kotlin.Metadata annotation.
     */
    fun getClassAnnotations(cls: Class<*>): List<String>

    /**
     * Get field annotations as comparable strings.
     */
    fun getFieldAnnotations(
        cls: Class<*>,
        fieldName: String
    ): List<String>

    /**
     * Get method signatures for comparison.
     * Returns list of method identifiers (name + signature).
     */
    fun getMethodSignatures(cls: Class<*>): List<MethodInfo>

    /**
     * Get constructor signatures for comparison.
     */
    fun getConstructorSignatures(cls: Class<*>): List<ConstructorInfo>
}

/**
 * Information about a method for comparison purposes.
 */
data class MethodInfo(
    val signature: String,
    val modifiers: Int,
    val annotations: List<String>
)

/**
 * Information about a constructor for comparison purposes.
 */
data class ConstructorInfo(
    val signature: String,
    val modifiers: Int,
    val annotations: List<String>
)
