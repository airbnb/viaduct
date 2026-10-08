package viaduct.arbitrary.common

/** Return an IntRange containing only this Int */
fun Int.asIntRange(): IntRange = IntRange(this, this)

/** Return a LongRange containing only this Int */
fun Int.asLongRange(): LongRange = this.toLong().asLongRange()

/** Return a LongRange containing only this Long */
fun Long.asLongRange(): LongRange = LongRange(this, this)
