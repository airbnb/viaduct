package viaduct.api.select

import viaduct.apiannotations.ExperimentalApi

/** Identifies a GraphQL schema field by its declaring type and field name. */
@ExperimentalApi
data class FieldCoordinate(
    val typeName: String,
    val fieldName: String,
)
