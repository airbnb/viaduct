package viaduct.engine.api

/**
 * Traverses a dot-separated path through nested [EngineObjectData.Sync] values to read a resolved field.
 *
 * Each element in [path] names a field key (or alias) to dereference from the current
 * [EngineObjectData.Sync]. The path may *end* on a value of any type (including lists and
 * [EngineObjectData.Sync]s), though it may not *traverse through* a list — attempting to do so throws.
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

    fun read(data: EngineObjectData.Sync): Any? = read(0, data)

    private tailrec fun read(
        pathIndex: Int,
        data: Any?
    ): Any? =
        if (pathIndex == path.size) {
            data
        } else if (data == null) {
            null
        } else {
            val segment = path[pathIndex]
            checkNotNull(data as? EngineObjectData.Sync) {
                "Expected an EngineObjectData.Sync at step $pathIndex of path $pathString, but found $data"
            }
            data as EngineObjectData.Sync
            val nextData = data.get(segment)
            read(pathIndex + 1, nextData)
        }
}
