package viaduct.tenant.runtime.context.factory

import graphql.language.FragmentDefinition
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLTypeUtil
import java.util.Locale.getDefault
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import viaduct.api.NodeResolverBase
import viaduct.api.ResolverBase
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ExecutionContext
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.VariablesProviderContext
import viaduct.api.internal.GRTConvFactory
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.Mutation
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.tenant.runtime.context.ConnectionFieldExecutionContextImpl
import viaduct.tenant.runtime.context.EngineExecutionContextWrapperImpl
import viaduct.tenant.runtime.context.FieldExecutionContextImpl
import viaduct.tenant.runtime.context.MutationFieldExecutionContextImpl
import viaduct.tenant.runtime.context.NodeExecutionContextImpl
import viaduct.tenant.runtime.context.VariablesProviderContextImpl
import viaduct.tenant.runtime.internal.InternalContextImpl
import viaduct.tenant.runtime.select.SelectionSetImpl
import viaduct.tenant.runtime.toInputLikeGRT

sealed class ResolverExecutionContextFactoryBase<R : CompositeOutput>(
    resolverBaseClass: Class<*>,
    expectedContextInterface: Class<out ResolverExecutionContext<*>>,
    protected val resultType: Type<CompositeOutput>,
) {
    @Suppress("UNCHECKED_CAST")
    private val wrapperContextCls: KClass<out ResolverExecutionContext<*>> =
        resolverBaseClass.declaredClasses.firstOrNull {
            expectedContextInterface.isAssignableFrom(it)
        }?.kotlin as? KClass<out ResolverExecutionContext<*>>
            ?: throw IllegalArgumentException("No nested Context class found in ${resolverBaseClass.name}")

    @Suppress("UNCHECKED_CAST")
    protected fun <CTX : ResolverExecutionContext<*>> wrap(ctx: CTX): CTX = wrapperContextCls.primaryConstructor!!.call(ctx) as CTX

    private val toNonCompositeSelectionSet: ResolverExecutionContextFactoryBase<R>.(EngineSelectionSet?) -> SelectionSet<R> = { sels ->
        require(sels == null) {
            "received a non-null selection set on a type declared as not-composite: ${resultType.kcls}"
        }
        @Suppress("UNCHECKED_CAST")
        SelectionSet.NoSelections as SelectionSet<R>
    }

    private val toCompositeSelectionSet: ResolverExecutionContextFactoryBase<R>.(EngineSelectionSet?) -> SelectionSet<R> = { sels ->
        require(sels != null) {
            "received a null selection set on a type declared as composite: ${resultType.kcls}"
        }
        @Suppress("UNCHECKED_CAST")
        SelectionSetImpl(resultType, sels) as SelectionSet<R>
    }

    protected val toSelectionSet: ResolverExecutionContextFactoryBase<R>.(EngineSelectionSet?) -> SelectionSet<R> =
        if (resultType.kcls == CompositeOutput.NotComposite::class) {
            toNonCompositeSelectionSet
        } else {
            toCompositeSelectionSet
        }
}

class NodeExecutionContextFactory(
    resolverBaseClass: Class<out NodeResolverBase<*>>,
    private val reflectionLoader: ReflectionLoader,
    resultType: Type<NodeObject>,
    private val grtConvFactory: GRTConvFactory,
    private val knownFragments: Map<String, FragmentDefinition> = emptyMap(),
) : ResolverExecutionContextFactoryBase<NodeObject>(
        resolverBaseClass,
        NodeExecutionContext::class.java,
        resultType
    ) {
    operator fun invoke(
        engineExecutionContext: EngineExecutionContext,
        selections: EngineSelectionSet?,
        requestContext: Any?,
        id: String
    ): NodeExecutionContext<*> {
        val internalContext = InternalContextImpl(engineExecutionContext.fullSchema, engineExecutionContext.globalIDCodec, reflectionLoader, grtConvFactory)
        val wrappedContext = NodeExecutionContextImpl(
            internalContext,
            EngineExecutionContextWrapperImpl(engineExecutionContext, knownFragments),
            this.toSelectionSet(selections),
            requestContext,
            internalContext.deserializeGlobalID(id)
        )
        return wrap(wrappedContext)
    }

    class FakeResolverBase<R : NodeObject> : NodeResolverBase<R> {
        class Context<R : NodeObject>(ctx: NodeExecutionContext<R>) : NodeExecutionContext<R> by ctx, InternalContext by (ctx as InternalContext)
    }
}

interface VariablesProviderContextFactory {
    fun createVariablesProviderContext(
        engineExecutionContext: EngineExecutionContext,
        requestContext: Any?,
        rawArguments: Map<String, Any?>
    ): VariablesProviderContext<Arguments>
}

class FieldExecutionContextFactory internal constructor(
    resolverBaseClass: Class<out ResolverBase<*>>,
    private val expectedContextInterface: Class<out BaseFieldExecutionContext<*, *, *>>,
    private val reflectionLoader: ReflectionLoader,
    resultType: Type<CompositeOutput>,
    private val argumentsCls: KClass<Arguments>,
    private val objectCls: KClass<Object>,
    private val queryCls: KClass<Query>,
    private val grtConvFactory: GRTConvFactory,
    private val graphqlTypeName: String? = null,
    private val graphqlFieldName: String? = null,
    private val knownFragments: Map<String, FragmentDefinition> = emptyMap(),
) : VariablesProviderContextFactory,
    ResolverExecutionContextFactoryBase<CompositeOutput>(
        resolverBaseClass,
        expectedContextInterface,
        resultType
    ) {
    @Suppress("UNCHECKED_CAST")
    operator fun invoke(
        engineExecutionContext: EngineExecutionContext,
        engineSelections: EngineSelectionSet?,
        requestContext: Any?,
        rawArguments: Map<String, Any?>,
        syncObjectValueGetter: (suspend () -> EngineObjectData.Sync)? = null,
        syncQueryValueGetter: (suspend () -> EngineObjectData.Sync)? = null,
    ): BaseFieldExecutionContext<*, *, *> {
        val internalContext = InternalContextImpl(engineExecutionContext.fullSchema, engineExecutionContext.globalIDCodec, reflectionLoader, grtConvFactory)
        val engineExecutionContextWrapper = EngineExecutionContextWrapperImpl(engineExecutionContext, knownFragments)

        val wrappedContext = when (expectedContextInterface) {
            ConnectionFieldExecutionContext::class.java -> ConnectionFieldExecutionContextImpl(
                internalContext,
                engineExecutionContextWrapper,
                this.toSelectionSet(engineSelections) as SelectionSet<Connection<*, *>>,
                requestContext,
                rawArguments.toInputLikeGRT(internalContext, argumentsCls, graphqlTypeName, graphqlFieldName) as ConnectionArguments,
                syncObjectValueGetter,
                syncQueryValueGetter,
                objectCls,
                queryCls,
            )

            FieldExecutionContext::class.java -> FieldExecutionContextImpl(
                internalContext,
                engineExecutionContextWrapper,
                this.toSelectionSet(engineSelections),
                requestContext,
                rawArguments.toInputLikeGRT(internalContext, argumentsCls, graphqlTypeName, graphqlFieldName),
                syncObjectValueGetter,
                syncQueryValueGetter,
                objectCls,
                queryCls,
            )

            MutationFieldExecutionContext::class.java -> MutationFieldExecutionContextImpl<Query, Mutation>(
                internalContext,
                engineExecutionContextWrapper,
                this.toSelectionSet(engineSelections),
                requestContext,
                rawArguments.toInputLikeGRT(internalContext, argumentsCls, graphqlTypeName, graphqlFieldName),
                syncQueryValueGetter,
                queryCls,
            )

            else -> throw IllegalArgumentException(
                "Expected context interface must be one of `ConnectionFieldExecutionContext`, `FieldExecutionContext`, or `MutationFieldExecutionContext` ($expectedContextInterface)."
            )
        }
        return wrap(wrappedContext)
    }

    override fun createVariablesProviderContext(
        engineExecutionContext: EngineExecutionContext,
        requestContext: Any?,
        rawArguments: Map<String, Any?>
    ): VariablesProviderContext<Arguments> {
        val ic = InternalContextImpl(engineExecutionContext.fullSchema, engineExecutionContext.globalIDCodec, reflectionLoader, grtConvFactory)
        return VariablesProviderContextImpl(ic, requestContext, rawArguments.toInputLikeGRT(ic, argumentsCls, graphqlTypeName, graphqlFieldName))
    }

    class FakeResolverBase<R : CompositeOutput> : ResolverBase<R> {
        class Context<O : Object, Q : Query, A : Arguments, R : CompositeOutput>(ctx: FieldExecutionContext<O, Q, A, R>) :
            FieldExecutionContext<O, Q, A, R> by ctx, InternalContext by (ctx as InternalContext)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun of(
            resolverBaseClass: Class<out ResolverBase<*>>,
            reflectionLoader: ReflectionLoader,
            typeName: String,
            fieldName: String,
            hasArguments: Boolean,
            queryTypeName: String,
            returnTypeName: String?,
            grtConvFactory: GRTConvFactory,
            knownFragments: Map<String, FragmentDefinition> = emptyMap(),
        ): FieldExecutionContextFactory {
            val expectedContextInterface = resolveExpectedContextInterface(resolverBaseClass)
            val queryCls = reflectionLoader.reflectionFor(queryTypeName).kcls as KClass<Query>
            val objectCls = reflectionLoader.reflectionFor(typeName).kcls as KClass<Object>
            val argumentsCls = resolveArgumentsCls(reflectionLoader, typeName, fieldName, hasArguments)
            // takeIf guards against enum GRTs: enums have a Reflection object so reflectionFor succeeds,
            // but they are not CompositeOutput and must not be treated as composite.
            val returnTypeKClass = returnTypeName?.let {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    reflectionLoader.reflectionFor(it).kcls
                        .takeIf { cls -> cls.isSubclassOf(CompositeOutput::class) } as KClass<CompositeOutput>?
                }.getOrNull()
            }
            val resultType = Type.ofClass(returnTypeKClass ?: CompositeOutput.NotComposite::class)

            return FieldExecutionContextFactory(
                resolverBaseClass,
                expectedContextInterface,
                reflectionLoader,
                resultType,
                argumentsCls,
                objectCls,
                queryCls,
                grtConvFactory,
                graphqlTypeName = typeName,
                graphqlFieldName = fieldName,
                knownFragments = knownFragments,
            )
        }

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
            reflectionLoader: ReflectionLoader,
            schema: ViaductSchema,
            typeName: String,
            fieldName: String,
            grtConvFactory: GRTConvFactory,
            knownFragments: Map<String, FragmentDefinition> = emptyMap(),
        ): FieldExecutionContextFactory {
            val fieldDef = schema.schema.getObjectType(typeName)?.getFieldDefinition(fieldName)
                ?: throw IllegalArgumentException("Called on a missing field coordinate ($typeName.$fieldName).")

            val expectedContextInterface = resolveExpectedContextInterface(resolverBaseClass)
            val queryCls = reflectionLoader.reflectionFor(schema.schema.queryType.name).kcls as KClass<Query>
            val objectCls = reflectionLoader.reflectionFor(typeName).kcls as KClass<Object>
            val argumentsCls = resolveArgumentsCls(reflectionLoader, typeName, fieldName, fieldDef.arguments.isNotEmpty())

            val resultType = Type.ofClass(
                (GraphQLTypeUtil.unwrapAll(fieldDef.type) as? GraphQLCompositeType)?.let { type ->
                    reflectionLoader.reflectionFor(type.name).kcls as KClass<CompositeOutput>
                } ?: CompositeOutput.NotComposite::class
            )

            return FieldExecutionContextFactory(
                resolverBaseClass,
                expectedContextInterface,
                reflectionLoader,
                resultType,
                argumentsCls,
                objectCls,
                queryCls,
                grtConvFactory,
                graphqlTypeName = typeName,
                graphqlFieldName = fieldName,
                knownFragments = knownFragments,
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun resolveExpectedContextInterface(resolverBaseClass: Class<out ResolverBase<*>>): Class<out BaseFieldExecutionContext<*, *, *>> {
            val contextKClass: KClass<out ExecutionContext> =
                resolverBaseClass.declaredClasses.firstOrNull {
                    BaseFieldExecutionContext::class.java.isAssignableFrom(it)
                }?.kotlin as? KClass<out ExecutionContext>
                    ?: throw IllegalArgumentException("No nested Context class found in ${resolverBaseClass.name}")

            return when {
                contextKClass.isSubclassOf(MutationFieldExecutionContext::class) ->
                    MutationFieldExecutionContext::class.java

                contextKClass.isSubclassOf(ConnectionFieldExecutionContext::class) ->
                    ConnectionFieldExecutionContext::class.java

                else ->
                    FieldExecutionContext::class.java
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun resolveArgumentsCls(
            reflectionLoader: ReflectionLoader,
            typeName: String,
            fieldName: String,
            hasArguments: Boolean,
        ): KClass<Arguments> =
            if (!hasArguments) {
                Arguments.NoArguments::class
            } else {
                val fn = fieldName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() }
                reflectionLoader.getGRTKClassFor("${typeName}_${fn}_Arguments")
            } as KClass<Arguments>
    }
}
