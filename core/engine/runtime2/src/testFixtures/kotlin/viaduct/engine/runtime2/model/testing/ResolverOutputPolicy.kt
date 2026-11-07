package viaduct.engine.runtime2.model.testing

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.NodeResolverFunction
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.selectiveFieldResolverOf
import viaduct.engine.runtime2.model.requireOutputType
import viaduct.graphql.schema.ViaductSchema

fun generatedFieldResolverOf(
    field: ViaductSchema.ObjectField,
    objectFragment: Fragment,
    queryFragment: Fragment,
    selectiveResolvers: Boolean,
    function: suspend (EngineObjectData.Sync, EngineObjectData.Sync, Arguments.Resolved) -> ResolverOutputData?,
): FieldResolverDefinition =
    if (selectiveResolvers) {
        selectiveFieldResolverOf(objectFragment, queryFragment) { input, query, arguments, demand ->
            function(input, query, arguments).snipToDemand(demand, field.outputType)
        }
    } else {
        fieldResolverOf(objectFragment, queryFragment, function)
    }

fun NodeResolverFunction.withGeneratedOutputPolicy(
    type: ViaductSchema.Object,
    selectiveResolvers: Boolean,
): NodeResolverFunction =
    if (selectiveResolvers) {
        NodeResolverFunction(if (mode == NodeResolverFunction.Mode.NONSELECTIVE) NodeResolverFunction.Mode.SELECTION_AWARE_NONSELECTIVE else mode) { id, demand, context ->
            invoke(id, demand, context).snipToDemand(demand, type.asTypeExpr().requireOutputType())
        }
    } else {
        this
    }
