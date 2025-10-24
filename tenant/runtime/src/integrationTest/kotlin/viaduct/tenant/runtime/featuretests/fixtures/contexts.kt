package viaduct.tenant.runtime.featuretests.fixtures

import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query

/** An alias for [FieldExecutionContext] that allows a resolver to read non-GRT data and write either GRT or non-GRT data */
typealias UntypedFieldContext = FieldExecutionContext<Object, Query, Arguments, CompositeOutput>

/** An alias for [MutationFieldExecution] that allows a mutation resolver to read non-GRT data and write either GRT or non-GRT data */
typealias UntypedMutationFieldContext = MutationFieldExecutionContext<Object, Query, Arguments, CompositeOutput>

/** An alias for [NodeExecutionContext] that allows a node resolver to read non-GRT data and write either GRT or non-GRT data */
typealias UntypedNodeContext = NodeExecutionContext<NodeObject>
