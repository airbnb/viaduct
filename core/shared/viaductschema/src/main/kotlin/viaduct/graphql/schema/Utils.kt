package viaduct.graphql.schema

import viaduct.utils.collections.BitVector

/** Unparses wrappers into an internal syntax.  This syntax
 *  uses an explicit '?' to represent a nullable type, and
 *  explicit '!' a not-null type.  A wrapper always has at
 *  least one of these characters indicating nullability
 *  for the base type.  It will have an additional such
 *  indicator for every depth of list.  Read from
 *  left-to-right, the left-most character indicates the
 *  nullability of the outermost value, the right-most of the
 *  base type.  Thus, for example, "?" by itself means the
 *  type has no list-wrappers, and that the value can be
 *  either null, or a value of the base-type, where "!?!"
 *  means a not-nullable list of nullable elements, each
 *  element, if not null, is itself a list of non-null
 *  elements having the bast-type as their type. */
fun <T : ViaductSchema.TypeDef> ViaductSchema.TypeExpr<T>.unparseWrappers(): String {
    val result = StringBuilder()
    for (i in 0 until listDepth) {
        result.append(if (nullableAtDepth(i)) '?' else '!')
    }
    result.append(if (baseTypeNullable) '?' else '!')
    return result.toString()
}

/** Parse our internal shorthand for wrappers (see
 *  [ViaductSchema.TypeExpr.unparseWrappers] for the syntax).  The method checks
 *  the entire string for syntactic correctness, so the
 *  caller of this method can always assume there
 *  `wrappers.charAt(wrappers.length-1)` -- which gives the
 *  nullability of the base type -- exists and is either
 *  '?' or '!'.  It returns a bit-vector whose length
 *  reflects the depth of list-wrapping (and thus might be
 *  zero) and whose elements reflect the nullability at
 *  each listing depth (where element '0' is the
 *  outermost list-wrapper). */
internal fun parseWrappers(wrappers: String): BitVector {
    val sz = wrappers.length
    if (sz == 0) throw IllegalArgumentException("At least wrapper needed for basetype ($wrappers).")
    val baseWrapper = wrappers[sz - 1]
    if (baseWrapper != '?' && baseWrapper != '!') {
        throw IllegalArgumentException("Bad wrapper syntax ($wrappers).")
    }
    if (sz == 1) return ViaductSchema.TypeExpr.NO_WRAPPERS
    val result = BitVector(wrappers.length - 1)
    for (i in 0..sz - 2) {
        when (wrappers[i]) {
            '?' -> result.set(i)
            '!' -> Unit // No-op -- result[i] already clear.
            else -> throw IllegalArgumentException("Bad wrapper syntax ($wrappers).")
        }
    }
    return result
}

/**
 * Extension function to create a TypeExpr from wrapper string notation.
 * This is the inverse of [unparseWrappers].
 */
fun ViaductSchema.toTypeExpr(
    wrappers: String,
    baseString: String
): ViaductSchema.TypeExpr<ViaductSchema.TypeDef> {
    val baseTypeDef = requireNotNull(this.types[baseString]) {
        "Type not found: $baseString"
    }
    val listNullable = parseWrappers(wrappers)
    val baseNullable = (wrappers.last() == '?')
    return ViaductSchema.TypeExpr(baseTypeDef, baseNullable, listNullable)
}
