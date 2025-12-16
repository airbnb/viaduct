package viaduct.api.mapping

import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.mkRawSelectionSet
import viaduct.engine.api.select.SelectionsParser
import viaduct.tenant.runtime.select.SelectionSetImpl

internal fun <T : CompositeOutput> mkSelectionSet(
    schema: ViaductSchema,
    type: Type<T>,
    selections: String,
    variables: Map<String, Any?> = emptyMap()
): SelectionSet<T> =
    SelectionSetImpl(
        type,
        mkRawSelectionSet(
            SelectionsParser.parse(type.name, selections),
            schema,
            variables
        )
    )
