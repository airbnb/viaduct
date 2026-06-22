package viaduct.tenant.runtime.select

import viaduct.api.documents.Selections
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.EngineSelectionSet
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.tenant.runtime.TenantApiInputValueNormalizer.normalizeVariablesForEngine

class SelectionSetFactoryImpl(
    private val engineSelectionSetFactory: EngineSelectionSet.Factory,
    private val globalIDCodec: GlobalIDCodec,
) : SelectionSetFactory {
    override fun <T : CompositeOutput> selectionsOn(
        type: Type<T>,
        @Selections selections: String,
        variables: Map<String, Any?>
    ): SelectionSet<T> =
        SelectionSetImpl(
            type,
            engineSelectionSetFactory.engineSelectionSet(
                typeName = type.name,
                selections,
                normalizeVariablesForEngine(variables, globalIDCodec),
            )
        )
}
