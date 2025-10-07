package viaduct.api.internal

import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query

/**
 * Transition interfaces to make ExecutionContext instantiation more pluggable.
 * These interfaces combine the public ExecutionContext interfaces with InternalContext
 * to provide a bridge during the refactoring process.
 */

interface NodeExecutionContextTmp<T : NodeObject> : NodeExecutionContext<T>, InternalContext

interface MutationFieldExecutionContextTmp<
    T : Object,
    Q : Query,
    A : Arguments,
    O : CompositeOutput
> : MutationFieldExecutionContext<T, Q, A, O>, InternalContext

interface FieldExecutionContextTmp<T : Object, Q : Query, A : Arguments, O : CompositeOutput> : FieldExecutionContext<T, Q, A, O>, InternalContext
