package viaduct.tenant.runtime.select

import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.select.Selections
import viaduct.api.types.CompositeOutput
import viaduct.apiannotations.TestingApi
import viaduct.engine.api.RawSelectionSet

@TestingApi
class SelectionSetFactoryImpl(
    private val rawSelectionSetFactory: RawSelectionSet.Factory
) : SelectionSetFactory {
    override fun <T : CompositeOutput> selectionsOn(
        type: Type<T>,
        @Selections selections: String,
        variables: Map<String, Any?>
    ): SelectionSet<T> =
        SelectionSetImpl(
            type,
            rawSelectionSetFactory.rawSelectionSet(typeName = type.name, selections, variables)
        )
}
