package viaduct.engine.runtime2.model.registry

import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.graphql.schema.ViaductSchema

/** One external from-field declaration compiled for canonical registry construction. */
class FromField private constructor(
    val responsePath: List<String>,
    internal val providerFragment: ProviderFragment,
    internal val fragment: Fragment,
    internal val keyPath: List<ObjectEngineResult.Key>,
    internal val terminalType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    internal val nullableTraversal: Boolean,
) : VariableDeclaration {
    internal fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): FromField =
        FromField(
            responsePath = responsePath,
            providerFragment = providerFragment,
            fragment = fragment.mapVariables(transform),
            keyPath = keyPath.mapVariables(transform),
            terminalType = terminalType,
            nullableTraversal = nullableTraversal,
        )

    internal fun isCompatibleWith(
        locationType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
        locationHasDefault: Boolean,
    ): Boolean =
        compatibleTypes(
            locationType = locationType,
            sourceType = terminalType.asInputType(),
            nullableTraversal = nullableTraversal,
            locationHasDefault = locationHasDefault,
        )

    internal fun isCompatibleWithInclusionCondition(): Boolean = terminalType.isCompatibleWithInclusionCondition(nullableTraversal)

    companion object {
        internal fun ofCompiled(
            responsePath: List<String>,
            providerFragment: ProviderFragment,
            fragment: Fragment,
            keyPath: List<ObjectEngineResult.Key>,
            terminalType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
            nullableTraversal: Boolean,
        ): FromField =
            FromField(
                responsePath,
                providerFragment,
                fragment,
                keyPath,
                terminalType,
                nullableTraversal,
            )
    }
}

@Suppress("UNCHECKED_CAST")
private fun ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>.asInputType(): ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef> {
    require(baseTypeDef is ViaductSchema.InputTypeDef)
    return this as ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>
}

internal tailrec fun compatibleTypes(
    locationType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    sourceType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    nullableTraversal: Boolean,
    locationHasDefault: Boolean,
): Boolean {
    val sourceEffectivelyNullable = nullableTraversal || sourceType.isNullable
    return when {
        locationHasDefault && sourceEffectivelyNullable ->
            compatibleTypes(
                locationType = locationType.withNullable(true),
                sourceType = sourceType,
                nullableTraversal = nullableTraversal,
                locationHasDefault = false,
            )

        !locationType.isNullable -> {
            val unwrappedLocation = locationType.withNullable(true)
            val unwrappedSource = sourceType.withNullable(true)
            if (sourceEffectivelyNullable) {
                false
            } else {
                compatibleTypes(
                    locationType = unwrappedLocation,
                    sourceType = unwrappedSource,
                    nullableTraversal = nullableTraversal,
                    locationHasDefault = false,
                )
            }
        }

        locationType.isList -> {
            val locationElement = checkNotNull(locationType.unwrapList())
            val sourceElement =
                sourceType.unwrapList()
                    ?: sourceType.withNullable(false)
            compatibleTypes(
                locationType = locationElement,
                sourceType = sourceElement,
                nullableTraversal = false,
                locationHasDefault = false,
            )
        }

        else ->
            !sourceType.isList &&
                locationType.baseTypeDef == sourceType.baseTypeDef
    }
}

private fun <T : ViaductSchema.TypeDef> ViaductSchema.TypeExpr<T>.withNullable(nullable: Boolean): ViaductSchema.TypeExpr<T> {
    return if (!isList) {
        ViaductSchema.TypeExpr(baseTypeDef, nullable)
    } else {
        val wrappers = listNullable.copy()
        if (nullable) wrappers.set(0) else wrappers.clear(0)
        ViaductSchema.TypeExpr(baseTypeDef, baseTypeNullable, wrappers)
    }
}
