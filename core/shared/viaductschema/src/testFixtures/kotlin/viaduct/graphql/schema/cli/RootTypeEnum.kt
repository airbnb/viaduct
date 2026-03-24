package viaduct.graphql.schema.cli

import viaduct.graphql.schema.ViaductSchema

/**
 * Enum for root operation types.
 */
enum class RootTypeEnum {
    QUERY,
    MUTATION,
    SUBSCRIPTION
}

/**
 * Returns the root type definition for the given root type enum.
 */
fun ViaductSchema.rootTypeDef(rootType: RootTypeEnum): ViaductSchema.Object? =
    when (rootType) {
        RootTypeEnum.QUERY -> queryTypeDef
        RootTypeEnum.MUTATION -> mutationTypeDef
        RootTypeEnum.SUBSCRIPTION -> subscriptionTypeDef
    }

/**
 * Based on Viaduct's @singleton directive, return the list of all
 * paths from a root type that consist of a sequence of @singleton
 * types followed by a non-singleton type.
 *
 * This identifies "root" fields - entry points into the graph that
 * clients can use to start queries.
 */
fun ViaductSchema.roots(rootType: RootTypeEnum): Iterator<List<String>> =
    object : Iterator<List<String>> {
        // Invariant: the field-path in [path] is a path from the root to the
        // type containing the field whose type's fields are being iterated by the
        // last element of [stack]. When [path] is empty, [stack] contains just the
        // field iterator of the root type.
        val path = mutableListOf<ViaductSchema.Field>()
        val stack = mutableListOf<Iterator<ViaductSchema.Field>>()

        var nextResult: List<String>? = null

        init {
            val rootDef = rootTypeDef(rootType)
            if (rootDef != null) {
                stack.add(rootDef.fields.iterator())
            }
            advance()
        }

        fun advance(): Boolean {
            while (stack.isNotEmpty()) {
                if (!stack.last().hasNext()) {
                    stack.removeLast()
                    if (path.isNotEmpty()) path.removeLast()
                } else {
                    val field = stack.last().next()
                    val baseTypeName = field.type.baseTypeDef.name

                    // Skip fields returning Query or Viewer when we're inside a singleton traversal
                    // (i.e., when path is not empty). This avoids infinite loops like
                    // Query->Viewer->query:Query->Viewer->... or Viewer->viewer:Viewer->...
                    // At the top level (path is empty), we DO want to traverse into Viewer.
                    if ((baseTypeName == "Query" || baseTypeName == "Viewer") && path.isNotEmpty()) {
                        continue
                    }

                    // If the field's type is not a singleton, it's a root
                    if (!field.type.baseTypeDef.hasAppliedDirective("singleton")) {
                        nextResult = path.map { it.name } + field.name
                        return true
                    }

                    // Otherwise, traverse into the singleton type
                    val baseDef = field.type.baseTypeDef
                    if (baseDef is ViaductSchema.Record) {
                        // Check for cycles
                        if (path.any { it.name == field.name && it.containingDef.name == field.containingDef.name }) {
                            throw IllegalArgumentException("$baseDef is recursive $path.")
                        }
                        stack.add(baseDef.fields.iterator())
                        path.add(field)
                    }
                }
            }
            nextResult = null
            return false
        }

        override fun hasNext() = (nextResult != null)

        override fun next(): List<String> {
            val result = nextResult ?: throw NoSuchElementException()
            advance()
            return result
        }
    }

/**
 * Collects all root field references for all root types into a set.
 * A field is considered a root if it appears in the roots() output for any root type.
 */
fun ViaductSchema.collectAllRootFields(): Set<ViaductSchema.Field> {
    val roots = mutableSetOf<ViaductSchema.Field>()

    for (rootType in RootTypeEnum.values()) {
        val rootTypeDef = rootTypeDef(rootType) ?: continue

        for (rootPath in roots(rootType)) {
            // Navigate to the field at this path
            var currentType: ViaductSchema.Record = rootTypeDef
            var field: ViaductSchema.Field? = null

            for (fieldName in rootPath) {
                field = currentType.field(fieldName)
                if (field == null) break

                val baseType = field.type.baseTypeDef
                if (baseType is ViaductSchema.Record) {
                    currentType = baseType
                }
            }

            if (field != null) {
                roots.add(field)
            }
        }
    }

    return roots
}
