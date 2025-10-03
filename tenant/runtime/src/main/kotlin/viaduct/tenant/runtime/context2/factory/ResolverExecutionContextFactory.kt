package viaduct.tenant.runtime.context2.factory

import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalIDCodec
import viaduct.api.internal.FieldExecutionContextTmp
import viaduct.api.internal.MutationFieldExecutionContextTmp
import viaduct.api.internal.NodeResolverBase
import viaduct.api.internal.ReflectionLoader
import viaduct.api.internal.ResolverBase
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RawSelectionSet
import viaduct.tenant.runtime.context2.FieldExecutionContextImpl
import viaduct.tenant.runtime.context2.MutationFieldExecutionContextImpl
import viaduct.tenant.runtime.context2.NodeExecutionContextImpl
import viaduct.tenant.runtime.context2.VariablesProviderContextImpl
import viaduct.tenant.runtime.internal.InternalContextImpl
import viaduct.tenant.runtime.select.SelectionSetImpl

sealed class ResolverExecutionContextFactoryBase<R : CompositeOutput>(
    resolverBaseClass: Class<*>,
    expectedContextInterface: Class<out ResolverExecutionContext>,
    protected val resultType: Type<CompositeOutput>,
) {
    @Suppress("UNCHECKED_CAST")
    private val wrapperContextCls: KClass<out ResolverExecutionContext> =
        resolverBaseClass.declaredClasses.firstOrNull {
            expectedContextInterface.isAssignableFrom(it)
        }?.kotlin as? KClass<out ResolverExecutionContext>
            ?: throw IllegalArgumentException("No nested Context class found in ${resolverBaseClass.name}")

    @Suppress("UNCHECKED_CAST")
    protected fun <CTX : ResolverExecutionContext> wrap(ctx: CTX): CTX = wrapperContextCls.primaryConstructor!!.call(ctx) as CTX

    private val toNonCompositeSelectionSet: ResolverExecutionContextFactoryBase<R>.(RawSelectionSet?) -> SelectionSet<R> = { sels ->
        require(sels == null) {
            "received a non-null selection set on a type declared as not-composite: ${resultType.kcls}"
        }
        @Suppress("UNCHECKED_CAST")
        SelectionSet.NoSelections as SelectionSet<R>
    }

    private val toCompositeSelectionSet: ResolverExecutionContextFactoryBase<R>.(RawSelectionSet?) -> SelectionSet<R> = { sels ->
        require(sels != null) {
            "received a null selection set on a type declared as composite: ${resultType.kcls}"
        }
        @Suppress("UNCHECKED_CAST")
        SelectionSetImpl(resultType, sels) as SelectionSet<R>
    }

    protected val toSelectionSet: ResolverExecutionContextFactoryBase<R>.(RawSelectionSet?) -> SelectionSet<R> =
        if (resultType.kcls == CompositeOutput.NotComposite::class) {
            toNonCompositeSelectionSet
        } else {
            toCompositeSelectionSet
        }
}

class NodeExecutionContextFactory(
    resolverBaseClass: Class<out NodeResolverBase<*>>,
    private val globalIDCodec: GlobalIDCodec,
    private val reflectionLoader: ReflectionLoader,
    resultType: Type<NodeObject>,
) : ResolverExecutionContextFactoryBase<NodeObject>(
        resolverBaseClass,
        NodeExecutionContext::class.java,
        resultType
    ) {
    operator fun invoke(
        engineExecutionContext: EngineExecutionContext,
        selections: RawSelectionSet?,
        id: String
    ): NodeExecutionContext<*> {
        val wrappedContext = NodeExecutionContextImpl(
            InternalContextImpl(engineExecutionContext.fullSchema, globalIDCodec, reflectionLoader),
            engineExecutionContext,
            this.toSelectionSet(selections),
            globalIDCodec.deserialize<NodeObject>(id)
        )
        return wrap(wrappedContext)
    }

    // visible for testing
    class FakeResolverBase<T : NodeObject> : NodeResolverBase<T> {
        class Context<T : NodeObject>(ctx: NodeExecutionContext<T>) : NodeExecutionContext<T> by ctx
    }
}

class RegularFieldExecutionContextFactory(
    resolverBaseClass: Class<out ResolverBase<*>>,
    private val globalIDCodec: GlobalIDCodec,
    private val reflectionLoader: ReflectionLoader,
    resultType: Type<CompositeOutput>,
    private val argumentsType: ArgumentsType<Arguments>,
    private val objectType: ObjectType<Object>,
    private val queryType: ObjectType<Query>,
) : ResolverExecutionContextFactoryBase<CompositeOutput>(
        resolverBaseClass,
        FieldExecutionContext::class.java,
        resultType
    ) {
    operator fun invoke(
        engineExecutionContext: EngineExecutionContext,
        rawSelections: RawSelectionSet?,
        rawArguments: Map<String, Any?>,
        rawObjectValue: EngineObjectData,
        rawQueryValue: EngineObjectData,
    ): FieldExecutionContextTmp<*, *, *, *> {
        val internalContext = InternalContextImpl(engineExecutionContext.fullSchema, globalIDCodec, reflectionLoader)
        val wrappedContext = FieldExecutionContextImpl(
            internalContext,
            engineExecutionContext,
            this.toSelectionSet(rawSelections),
            argumentsType.makeGRT(internalContext, rawArguments),
            objectType.makeGRT(internalContext, rawObjectValue),
            queryType.makeGRT(internalContext, rawQueryValue),
        )
        return wrap(wrappedContext)
    }

    // visible for testing
    class FakeResolverBase<O : CompositeOutput> : ResolverBase<O> {
        class Context<T : Object, Q : Query, A : Arguments, O : CompositeOutput>(ctx: FieldExecutionContext<T, Q, A, O>) :
            FieldExecutionContext<T, Q, A, O> by ctx
    }
}

class MutationFieldExecutionContextFactory<T : Object, Q : Query, A : Arguments, O : CompositeOutput>(
    resolverBaseClass: Class<out ResolverBase<O>>,
    private val globalIDCodec: GlobalIDCodec,
    private val reflectionLoader: ReflectionLoader,
    resultType: Type<CompositeOutput>,
    private val argumentsType: ArgumentsType<Arguments>,
    private val objectType: ObjectType<Object>,
    private val queryType: ObjectType<Query>,
) : ResolverExecutionContextFactoryBase<CompositeOutput>(
        resolverBaseClass,
        MutationFieldExecutionContext::class.java,
        resultType
    ) {
    operator fun invoke(
        engineExecutionContext: EngineExecutionContext,
        rawSelections: RawSelectionSet?,
        rawArguments: Map<String, Any?>,
        rawObjectValue: EngineObjectData,
        rawQueryValue: EngineObjectData,
    ): MutationFieldExecutionContextTmp<Object, Query, Arguments, CompositeOutput> {
        val internalContext = InternalContextImpl(engineExecutionContext.fullSchema, globalIDCodec, reflectionLoader)
        val wrappedContext = MutationFieldExecutionContextImpl(
            internalContext,
            engineExecutionContext,
            this.toSelectionSet(rawSelections),
            argumentsType.makeGRT(internalContext, rawArguments),
            objectType.makeGRT(internalContext, rawObjectValue),
            queryType.makeGRT(internalContext, rawQueryValue),
        )
        return wrap(wrappedContext)
    }

    // visible for testing
    class FakeResolverBase<O : CompositeOutput> : ResolverBase<O> {
        class Context<T : Object, Q : Query, A : Arguments, O : CompositeOutput>(ctx: MutationFieldExecutionContext<T, Q, A, O>) :
            MutationFieldExecutionContext<T, Q, A, O> by ctx
    }
}

class VariablesProviderContextFactory(
    private val globalIDCodec: GlobalIDCodec,
    private val reflectionLoader: ReflectionLoader,
) {
    operator fun <A : Arguments> invoke(
        engineExecutionContext: EngineExecutionContext,
        args: A
    ) = VariablesProviderContextImpl(InternalContextImpl(engineExecutionContext.fullSchema, globalIDCodec, reflectionLoader), args)
}
