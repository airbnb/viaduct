package viaduct.graphql.utils

import graphql.language.Directive

/**
 * Extensions to make interfacing with graphql-java easier.
 */
fun List<Directive>?.ensureOneDirective(): Directive? {
    if (this != null && this.size > 1) {
        throw RuntimeException("Got unexpected multiple values for directive $this")
    }
    return this?.firstOrNull()
}
