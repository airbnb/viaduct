package viaduct.engine.api

/**
 * Traverses a dot-separated path through nested [EngineObjectData] values to read a resolved field.
 *
 * Each element in [path] names a field key (or alias) to dereference from the current
 * [EngineObjectData]. The path may *end* on a value of any type (including lists and
 * [EngineObjectData]s), though it may not *traverse through* a list — attempting to do so throws.
 * If any intermediate node is `null` the read short-circuits and returns `null`.
 *
 * @property path A non-empty list of field-key segments that form the traversal path.
 *   Each segment must be non-empty.
 */
class EngineDataReader(private val path: List<String>) {
    private val pathString = path.joinToString(".")

    init {
        require(path.all { it.isNotEmpty() }) {
            "Path contains empty elements: ${path.joinToString()}"
        }
    }

    suspend fun read(data: EngineObjectData): Any? = read(0, data)

    private tailrec suspend fun read(
        pathIndex: Int,
        data: Any?
    ): Any? =
        if (pathIndex == path.size) {
            data
        } else if (data == null) {
            null
        } else {
            val segment = path[pathIndex]
            checkNotNull(data as? EngineObjectData) {
                "Expected an EngineObjectData at step $pathIndex of path $pathString, but found $data"
            }
            data as EngineObjectData
            val nextData = data.fetch(segment)
            read(pathIndex + 1, nextData)
        }
}
