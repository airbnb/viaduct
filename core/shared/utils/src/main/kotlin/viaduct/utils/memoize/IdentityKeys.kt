package viaduct.utils.memoize

/** A tuple key that compares its components by reference identity. */
class IdentityPair<A, B>(val first: A, val second: B) {
    override fun equals(other: Any?): Boolean = other is IdentityPair<*, *> && first === other.first && second === other.second

    override fun hashCode(): Int = 31 * System.identityHashCode(first) + System.identityHashCode(second)
}

/** A tuple key that compares its components by reference identity. */
class IdentityTriple<A, B, C>(val first: A, val second: B, val third: C) {
    override fun equals(other: Any?): Boolean = other is IdentityTriple<*, *, *> && first === other.first && second === other.second && third === other.third

    override fun hashCode(): Int = 31 * (31 * System.identityHashCode(first) + System.identityHashCode(second)) + System.identityHashCode(third)
}

/** A tuple key that compares its components by reference identity. */
class IdentityQuadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D) {
    override fun equals(other: Any?): Boolean = other is IdentityQuadruple<*, *, *, *> && first === other.first && second === other.second && third === other.third && fourth === other.fourth

    override fun hashCode(): Int {
        var result = System.identityHashCode(first)
        result = 31 * result + System.identityHashCode(second)
        result = 31 * result + System.identityHashCode(third)
        return 31 * result + System.identityHashCode(fourth)
    }
}

/** A tuple key that compares its components by reference identity. */
class IdentityQuintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E) {
    override fun equals(other: Any?): Boolean =
        other is IdentityQuintuple<*, *, *, *, *> && first === other.first && second === other.second && third === other.third && fourth === other.fourth && fifth === other.fifth

    override fun hashCode(): Int {
        var result = System.identityHashCode(first)
        result = 31 * result + System.identityHashCode(second)
        result = 31 * result + System.identityHashCode(third)
        result = 31 * result + System.identityHashCode(fourth)
        return 31 * result + System.identityHashCode(fifth)
    }
}

/** A tuple key that compares its components by reference identity. */
class IdentitySextuple<A, B, C, D, E, F>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E, val sixth: F) {
    override fun equals(other: Any?): Boolean =
        other is IdentitySextuple<*, *, *, *, *, *> && first === other.first && second === other.second && third === other.third && fourth === other.fourth && fifth === other.fifth && sixth === other.sixth

    override fun hashCode(): Int {
        var result = System.identityHashCode(first)
        result = 31 * result + System.identityHashCode(second)
        result = 31 * result + System.identityHashCode(third)
        result = 31 * result + System.identityHashCode(fourth)
        result = 31 * result + System.identityHashCode(fifth)
        return 31 * result + System.identityHashCode(sixth)
    }
}

/** A tuple key that compares its components by reference identity. */
class IdentitySeptuple<A, B, C, D, E, F, G>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E, val sixth: F, val seventh: G) {
    override fun equals(other: Any?): Boolean =
        other is IdentitySeptuple<*, *, *, *, *, *, *> && first === other.first && second === other.second && third === other.third && fourth === other.fourth && fifth === other.fifth && sixth === other.sixth && seventh === other.seventh

    override fun hashCode(): Int {
        var result = System.identityHashCode(first)
        result = 31 * result + System.identityHashCode(second)
        result = 31 * result + System.identityHashCode(third)
        result = 31 * result + System.identityHashCode(fourth)
        result = 31 * result + System.identityHashCode(fifth)
        result = 31 * result + System.identityHashCode(sixth)
        return 31 * result + System.identityHashCode(seventh)
    }
}
