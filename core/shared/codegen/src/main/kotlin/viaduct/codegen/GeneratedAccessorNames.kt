package viaduct.codegen

/** Collision detection over the accessor names a GRT generator is about to emit for one composite type. */
object GeneratedAccessorNames {
    /**
     * Throws [IllegalArgumentException] if two fields would generate the same accessor name.
     *
     * @param typeName the composite type being generated, used in the error message.
     * @param baseAccessorNames field name to the base accessor name generated for it, in schema
     *   order. Pass the names this generator computes, not raw field names: the back-ends disagree
     *   on them, and on which suffixes they emit.
     * @param suffixes the suffixes this generator appends to each base accessor name.
     */
    @JvmStatic
    fun validateNoCollisions(
        typeName: String,
        baseAccessorNames: Map<String, String>,
        suffixes: Collection<String>,
    ) {
        require(suffixes.distinct().size == suffixes.size) {
            "Duplicate accessor suffixes for `$typeName`: $suffixes. Each suffix must be distinct, " +
                "or a single field would generate the same accessor twice."
        }

        val owners = mutableMapOf<String, String>()
        val collisions = mutableListOf<String>()
        val reportedPairs = mutableSetOf<Pair<String, String>>()

        for ((fieldName, baseAccessorName) in baseAccessorNames) {
            for (suffix in suffixes) {
                val accessor = baseAccessorName + suffix
                val owner = owners.putIfAbsent(accessor, fieldName)
                // Distinct suffixes and one entry per field name mean a field cannot collide with
                // itself, so any hit is a real pair.
                if (owner != null && reportedPairs.add(owner to fieldName)) {
                    collisions.add("fields `$owner` and `$fieldName` both generate `$accessor`")
                }
            }
        }

        if (collisions.isEmpty()) return

        val suffixList = suffixes.joinToString(", ", "[", "]") { it.ifEmpty { "<none>" } }
        throw IllegalArgumentException(
            "Generated accessor name collision on type `$typeName`: ${collisions.joinToString("; ")}. " +
                "Generated accessors are a field's accessor name plus one of $suffixList, so two fields " +
                "whose accessor names differ only by one of those suffixes, or that map to the same " +
                "accessor name, cannot coexist on the same type. Rename one of the fields."
        )
    }
}
