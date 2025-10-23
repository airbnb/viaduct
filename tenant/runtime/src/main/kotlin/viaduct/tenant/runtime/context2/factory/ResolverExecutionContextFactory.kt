package viaduct.tenant.runtime.context2.factory

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLTypeUtil
import java.util.Locale.getDefault
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import viaduct.api.context.ExecutionContext
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.VariablesProviderContext
import viaduct.api.globalid.GlobalIDCodec
import viaduct.api.internal.InternalContext
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
import viaduct.engine.api.ViaductSchema
import viaduct.tenant.runtime.context2.FieldExecutionContextImpl
import viaduct.tenant.runtime.context2.MutationFieldExecutionContextImpl
import viaduct.tenant.runtime.context2.NodeExecutionContextImpl
import viaduct.tenant.runtime.context2.VariablesProviderContextImpl
import viaduct.tenant.runtime.internal.InternalContextImpl
import viaduct.tenant.runtime.select.SelectionSetImpl
import viaduct.tenant.runtime.toInputLikeGRT
import viaduct.tenant.runtime.toObjectGRT

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
        requestContext: Any?,
        id: String
    ): NodeExecutionContext<*> {
        val wrappedContext = NodeExecutionContextImpl(
            InternalContextImpl(engineExecutionContext.fullSchema, globalIDCodec, reflectionLoader),
            engineExecutionContext,
            this.toSelectionSet(selections),
            requestContext,
            globalIDCodec.deserialize<NodeObject>(id)
        )
        return wrap(wrappedContext)
    }

    // visible for testing
    class FakeResolverBase<T : NodeObject> : NodeResolverBase<T> {
        class Context<T : NodeObject>(ctx: NodeExecutionContext<T>) : NodeExecutionContext<T> by ctx, InternalContext by (ctx as InternalContext)
    }
}

interface VariablesProviderContextFactory {
    fun createVariablesProviderContext(
        engineExecutionContext: EngineExecutionContext,
        requestContext: Any?,
        rawArguments: Map<String, Any?>
    ): VariablesProviderContext<Arguments>
}

class FieldExecutionContextFactory private constructor(
    resolverBaseClass: Class<out ResolverBase<*>>,
    expectedContextInterface: Class<out ResolverExecutionContext>,
    private val globalIDCodec: GlobalIDCodec,
    private val reflectionLoader: ReflectionLoader,
    resultType: Type<CompositeOutput>,
    private val argumentsCls: KClass<Arguments>,
    private val objectCls: KClass<Object>,
    private val queryCls: KClass<Query>,
) : VariablesProviderContextFactory,
    ResolverExecutionContextFactoryBase<CompositeOutput>(
        resolverBaseClass,
        expectedContextInterface,
        resultType
    ) {
    val ctor: KFunction<FieldExecutionContext<*, *, *, *>> = when (expectedContextInterface) {
        FieldExecutionContext::class.java -> FieldExecutionContextImpl::class.primaryConstructor!!
        MutationFieldExecutionContext::class.java -> MutationFieldExecutionContextImpl::class.primaryConstructor!!
        else -> throw IllegalArgumentException("Expected context interface must be one of `FieldExecutionContext` or `MutationFieldExecutionContext` ($expectedContextInterface).")
    }

    operator fun invoke(
        engineExecutionContext: EngineExecutionContext,
        rawSelections: RawSelectionSet?,
        requestContext: Any?,
        rawArguments: Map<String, Any?>,
        rawObjectValue: EngineObjectData,
        rawQueryValue: EngineObjectData,
    ): FieldExecutionContext<*, *, *, *> {
        val internalContext = InternalContextImpl(engineExecutionContext.fullSchema, globalIDCodec, reflectionLoader)
        val wrappedContext = ctor.call(
            internalContext,
            engineExecutionContext,
            this.toSelectionSet(rawSelections),
            requestContext,
            rawArguments.toInputLikeGRT(internalContext, argumentsCls),
            rawObjectValue.toObjectGRT(internalContext, objectCls),
            rawQueryValue.toObjectGRT(internalContext, queryCls),
        )
        return wrap(wrappedContext)
    }

    override fun createVariablesProviderContext(
        engineExecutionContext: EngineExecutionContext,
        requestContext: Any?,
        rawArguments: Map<String, Any?>
    ): VariablesProviderContext<Arguments> {
        val ic = InternalContextImpl(engineExecutionContext.fullSchema, globalIDCodec, reflectionLoader)
        return VariablesProviderContextImpl(ic, requestContext, rawArguments.toInputLikeGRT(ic, argumentsCls))
    }

    // visible for testing
    class FakeResolverBase<O : CompositeOutput> : ResolverBase<O> {
        class Context<T : Object, Q : Query, A : Arguments, O : CompositeOutput>(ctx: FieldExecutionContext<T, Q, A, O>) :
            FieldExecutionContext<T, Q, A, O> by ctx, InternalContext by (ctx as InternalContext)
    }

    companion object {
        /**
         * Returns a field execution context factory for a field def.  Could be
         * a "regular" or "mutation" context factory based on the type of the
         * nested `Context` class found in [resolverBaseClass].
         *
         * Called by module bootstrapper only when a field exists and has a resolver on it.
         * Thus, assumes `typeName.fieldName` is a valid field coordinate in [schema].
         */
        @Suppress("UNCHECKED_CAST")
        fun of(
            resolverBaseClass: Class<out ResolverBase<*>>,
            globalIDCodec: GlobalIDCodec,
            reflectionLoader: ReflectionLoader,
            schema: ViaductSchema,
            typeName: String,
            fieldName: String,
        ): FieldExecutionContextFactory {
            val fieldDef = schema.schema.getObjectType(typeName)?.getFieldDefinition(fieldName)
                ?: throw IllegalArgumentException("Called on a missing field coordinate ($typeName.$fieldName).")

            val contextKClass: KClass<out ExecutionContext> =
                resolverBaseClass.declaredClasses.firstOrNull {
                    FieldExecutionContext::class.java.isAssignableFrom(it)
                }?.kotlin as? KClass<out ExecutionContext>
                    ?: throw IllegalArgumentException("No nested Context class found in ${resolverBaseClass.name}")

            val expectedContextInterface: Class<out ResolverExecutionContext> =
                if (contextKClass.isSubclassOf(MutationFieldExecutionContext::class)) {
                    MutationFieldExecutionContext::class.java
                } else {
                    FieldExecutionContext::class.java
                }

            val queryCls = reflectionLoader.reflectionFor(schema.schema.queryType.name).kcls as KClass<Query>

            val objectCls = reflectionLoader.reflectionFor(typeName).kcls as KClass<Object>

            val argumentsCls = if (fieldDef.arguments.isEmpty()) {
                Arguments.NoArguments::class
            } else {
                val fn = fieldName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() }
                reflectionLoader.reflectionFor("${typeName}_${fn}_Arguments").kcls
            } as KClass<Arguments>

            val resultType = Type.ofClass(
                (GraphQLTypeUtil.unwrapAll(fieldDef.type) as? GraphQLCompositeType)?.let { type ->
                    reflectionLoader.reflectionFor(type.name).kcls as KClass<CompositeOutput>
                } ?: CompositeOutput.NotComposite::class
            )

            return FieldExecutionContextFactory(
                resolverBaseClass,
                expectedContextInterface,
                globalIDCodec,
                reflectionLoader,
                resultType,
                argumentsCls,
                objectCls,
                queryCls,
            )
        }
    }
}
