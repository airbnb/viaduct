package viaduct.engine.api

/**
 * @property path a path into an input value. The path may *end* on a value
 * of any type (including lists and maps), though it may not *traverse through* a list or be empty
 */
@JvmInline
internal value class InputValueReader(private val path: List<String>) {
    init {
        require(path.isNotEmpty()) {
            "path may not be empty"
        }
    }

    /** Read a value at [path] in [map], returning null if any step value is null */
    fun read(map: Map<String, Any?>): Any? {
        var result: Any = map
        for (segment in path) {
            @Suppress("UNCHECKED_CAST")
            val valueAsMap = checkNotNull(result as? Map<String, Any>) {
                "Expected Map value but found: $result"
            }

            val v = valueAsMap[segment]
            if (v == null) {
                return v
            } else {
                result = v
            }
        }
        return result
    }
}
