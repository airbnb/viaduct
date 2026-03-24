package viaduct.api.internal

import viaduct.apiannotations.InternalApi

/** Used to annotate generated node resolver base classes */
@Target(AnnotationTarget.CLASS)
@InternalApi
annotation class NodeResolverFor(
    val typeName: String,
)
