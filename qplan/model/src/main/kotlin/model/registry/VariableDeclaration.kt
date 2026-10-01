package model.registry

import viaduct.graphql.schema.ViaductSchema

/** A source declaration compiled into one canonical field-relative variable definition. */
sealed interface VariableDeclaration

/** Whether this source type always produces the non-null Boolean required by a condition. */
internal fun ViaductSchema.TypeExpr<*>.isCompatibleWithInclusionCondition(nullableTraversal: Boolean): Boolean =
    !nullableTraversal &&
        !isNullable &&
        !isList &&
        baseTypeDef is ViaductSchema.Scalar &&
        baseTypeDef.name == "Boolean"
