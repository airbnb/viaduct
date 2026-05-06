package viaduct.api.internal

import graphql.GraphQLContext
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.apiannotations.Attribution
import viaduct.apiannotations.AttributionContext
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RootFieldReference
import viaduct.errors.FrameworkException
import viaduct.errors.TenantUsageException
import viaduct.errors.UnsetFieldException
import viaduct.errors.handleFrameworkErrors

/**
 * Base class for object type GRTs
 */
@InternalApi
abstract class ObjectBase(
    protected val context: InternalContext,
    val engineObject: EngineObject,
) : Object {
    private val fieldCache = ConcurrentHashMap<String, Any>()

    /**
     * Fetches a field value, throwing on failure. Called by generated GRT getters.
     * For internal framework use only.
     */
    protected fun <T> fetch(
        fieldName: String,
        baseFieldTypeClass: KClass<*>,
        alias: String? = null
    ): T =
        handleFrameworkErrors("${engineObject.type.name}.$fieldName") {
            get(fieldName, baseFieldTypeClass, alias)
        }

    /**
     * Fetches a field value, returning `null` on failure instead of throwing. Called by generated
     * GRT `getXxxOrNull` getters. For internal framework use only.
     */
    protected fun <T> fetchOrNull(
        fieldName: String,
        baseFieldTypeClass: KClass<*>,
        alias: String? = null
    ): T? =
        handleFrameworkErrors("${engineObject.type.name}.$fieldName") {
            getOrNull(fieldName, baseFieldTypeClass, alias)
        }

    /**
     * Fetches the given selection from the EngineObjectData and wraps it into a typed GRT or scalar value.
     * Called by generated GRT field getters and also directly by tenant code for backing data fields,
     * for which no getter is generated.
     *
     * @param fieldName the name of the field being fetched
     * @param baseFieldTypeClass the KClass that represents the generated base type of the provided selection
     * @param alias the GraphQL response key of the selection, if aliased (see https://spec.graphql.org/draft/#sec-Field-Alias)
     */
    @Suppress("UNCHECKED_CAST")
    @Attribution(AttributionContext.FRAMEWORK)
    fun <T> get(
        fieldName: String,
        baseFieldTypeClass: KClass<*>,
        alias: String? = null
    ): T {
        return getInternal(alias, fieldName, baseFieldTypeClass) { eod, selection ->
            eod.get(selection) as T
        }
    }

    /**
     * Fetches the given selection from the EngineObjectData and wraps it into a typed GRT or scalar value,
     * returning `null` if the field value is absent. Public function called by generated GRT `getXxxOrNull` getters.
     *
     * @param fieldName the name of the field being fetched
     * @param baseFieldTypeClass the KClass that represents the generated base type of the provided selection
     * @param alias the GraphQL response key of the selection, if aliased (see https://spec.graphql.org/draft/#sec-Field-Alias)
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getOrNull(
        fieldName: String,
        baseFieldTypeClass: KClass<*>,
        alias: String? = null
    ): T? {
        return getInternal(alias, fieldName, baseFieldTypeClass) { eod, selection ->
            eod.getOrNull(selection) as T?
        }
    }

    private fun <T> getInternal(
        alias: String?,
        fieldName: String,
        baseFieldTypeClass: KClass<*>,
        extractingFunction: (eod: EngineObjectData.Sync, selection: String) -> T
    ): T {
        val selection = alias ?: fieldName
        val result = fieldCache.getOrPut(selection) {
            val objectType = engineObject.type
            val fieldDefinition = objectType.getField(fieldName) ?: throw FrameworkException(
                "Field $fieldName not found on type ${objectType.name}"
            )
            handleFrameworkErrors("${objectType.name}.$selection") {
                val fieldValue = when (engineObject) {
                    is NodeReference -> {
                        // If the EOD is a node reference, only allow access to its ID field
                        if (selection == "id") {
                            engineObject.id
                        } else {
                            throw UnsetFieldException(
                                selection,
                                objectType,
                                "only id can be accessed on an unresolved Node reference created using Context.nodeRef"
                            )
                        }
                    }
                    is RootFieldReference -> {
                        throw UnsetFieldException(
                            selection,
                            objectType,
                            "fields cannot be accessed on an unresolved root field reference created using Context.rootFieldRef"
                        )
                    }
                    is EngineObjectData.Sync -> extractingFunction(engineObject, selection)
                    is EngineObjectData -> throw FrameworkException(
                        "Expected EngineObjectData.Sync but got ${engineObject.javaClass.name} for ${objectType.name}.$fieldName"
                    )
                    else -> throw FrameworkException("Unknown EngineObject subclass ${engineObject.javaClass.name}")
                }
                wrap(fieldDefinition.type, fieldValue, baseFieldTypeClass)
            } ?: NULL_VALUE
        }
        return (if (result == NULL_VALUE) null else result) as T
    }

    @Attribution(AttributionContext.FRAMEWORK)
    private fun wrap(
        type: GraphQLType,
        value: Any?,
        baseFieldTypeClass: KClass<*>
    ): Any? {
        if (value == null) {
            if (GraphQLTypeUtil.isNonNull(type)) {
                throw TenantUsageException("Got null value for non-null type ${GraphQLTypeUtil.simplePrint(type)}")
            }
            return null
        }

        return when (val unwrappedType = GraphQLTypeUtil.unwrapNonNull(type)) {
            is GraphQLScalarType -> wrapScalar(unwrappedType, value, baseFieldTypeClass)
            is GraphQLEnumType -> wrapEnum(context, unwrappedType, value)
            is GraphQLList -> wrapList(unwrappedType, value, baseFieldTypeClass)
            is GraphQLCompositeType -> wrapObject(unwrappedType, value)
            else -> throw FrameworkException("Unexpected type ${GraphQLTypeUtil.simplePrint(unwrappedType)}")
        }
    }

    @Attribution(AttributionContext.FRAMEWORK)
    private fun wrapScalar(
        type: GraphQLScalarType,
        value: Any,
        baseFieldTypeClass: KClass<*>
    ): Any {
        // The DateTime scalar type coerces to OffsetDateTime, but we use Instant for GRTs
        if (type.name == "DateTime") {
            return when (value) {
                is Instant -> value
                is String -> OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
                else -> throw TenantUsageException("Could not convert $value to Instant.")
            }
        } else if (type.name == "JSON") {
            return value
        } else if (type.name == "BackingData") {
            if (value::class != baseFieldTypeClass) {
                throw TenantUsageException(
                    "Expected backing data value to be of type ${baseFieldTypeClass.simpleName}, got ${value::class.simpleName}"
                )
            }
            return value
        } else if (baseFieldTypeClass == GlobalID::class) {
            return context.deserializeGlobalID<NodeObject>(value as String)
        }
        return type.coercing.parseValue(value, GraphQLContext.getDefault(), Locale.getDefault()) ?: throw TenantUsageException(
            "Failed to parse value $value for scalar type ${type.name}"
        )
    }

    @Attribution(AttributionContext.FRAMEWORK)
    private fun wrapList(
        type: GraphQLList,
        value: Any,
        baseFieldTypeClass: KClass<*>
    ): List<*> {
        if (value !is List<*>) {
            throw TenantUsageException("Got non-list value $value for list type")
        }
        return value.map {
            wrap(GraphQLTypeUtil.unwrapOne(type), it, baseFieldTypeClass)
        }
    }

    @Attribution(AttributionContext.FRAMEWORK)
    private fun wrapObject(
        type: GraphQLCompositeType,
        value: Any
    ): ObjectBase {
        if (value !is EngineObject) {
            throw TenantUsageException("Expected value to be an instance of EngineObjectData, got $value")
        }

        val valueType = context.reflectionLoader.reflectionFor(value.type.name)

        if (type is GraphQLObjectType) {
            require(type.name == value.type.name) {
                "Expected value with GraphQL type ${type.name}, got ${value.type.name}"
            }
        } else {
            // type is an interface or union
            val typeType = context.reflectionLoader.reflectionFor(type.name)
            require(valueType.kcls.isSubclassOf(typeType.kcls)) {
                "Expected value to be a subtype of ${type.name}, got ${valueType.name}"
            }
        }
        require(valueType.kcls.isSubclassOf(ObjectBase::class)) {
            "Expected baseFieldTypeClass that's a subtype of ObjectBase, got ${valueType.kcls}"
        }

        @Suppress("UNCHECKED_CAST")
        return wrapOutputObject(context, valueType as Type<Object>, value) as ObjectBase
    }

    /**
     * Helper method for generated toBuilder() implementations.
     * Returns the EngineObjectData for this GRT instance, throwing if called on an
     * unresolved reference (NodeReference or RootFieldReference).
     *
     * @return The EngineObjectData backing this GRT
     * @throws TenantUsageException if called on an unresolved reference
     */
    protected fun toBuilderEOD(): EngineObjectData.Sync {
        if (engineObject is NodeReference) {
            throw TenantUsageException(
                "Cannot call toBuilder() on an unresolved NodeReference."
            )
        }
        if (engineObject is RootFieldReference) {
            throw TenantUsageException(
                "Cannot call toBuilder() on an unresolved RootFieldReference."
            )
        }

        return engineObject as EngineObjectData.Sync
    }

    /**
     * Returns the InternalContext for use in generated toBuilder() implementations.
     * Using this method avoids JVM VerifyErrors when a GRT has a GraphQL field named "context",
     * which would generate a getContext() method that conflicts with the inherited property accessor.
     */
    protected fun getBuilderContext(): InternalContext = context

    /**
     * Returns the EngineObject for use in generated toBuilder() implementations.
     * Using this method avoids JVM VerifyErrors when a GRT has a GraphQL field named "engineObject",
     * which would generate a getEngineObject() method that conflicts with the inherited property accessor.
     */
    protected fun getBuilderEngineObject(): EngineObject = engineObject

    /**
     * Usually directly used by tenant developers to build Viaduct object in resolvers by calling
     * `MyType.Builder(context)`, where `MyType` is a generated GRT class that extends ObjectBase class.
     *
     * Can also be constructed with a base EOD to enable calling `toBuilder` on GRTs.
     */
    abstract class Builder<T>(
        protected val context: InternalContext,
        private val type: GraphQLObjectType,
        private val baseEngineObjectData: EngineObjectData.Sync?
    ) : DynamicOutputValueBuilder<T> {
        private val wrapper = EODBuilderWrapper(type, context.globalIDCodec)

        protected fun buildEngineObjectData(): EngineObjectData.Sync =
            handleFrameworkErrors("ObjectBase.Builder.buildEngineObjectData failed") {
                val overlay = wrapper.getEngineObjectData() as EngineObjectData.Sync
                baseEngineObjectData?.let { base ->
                    OverlayEngineObjectData(overlay, base)
                } ?: overlay
            }

        /**
         * Returns the InternalContext for use in generated build() implementations.
         * Using this method avoids JVM VerifyErrors when a GRT has a GraphQL field named "context",
         * which would generate a getContext() method that conflicts with the inherited property accessor.
         */
        protected fun getBuilderContext(): InternalContext = context

        /**
         * Called by strictly typed static builder-setters in generated GRT
         * to put a field value into the EngineObjectData.
         */
        protected fun putInternal(
            fieldName: String,
            value: Any?
        ) = handleFrameworkErrors("ObjectBase.Builder.putInternal failed") {
            wrapper.put(fieldName, value)
        }

        /**
         * Dynamic builder function with type check
         */
        final override fun put(
            name: String,
            value: Any?,
        ): Builder<T> {
            typeCheck(name, value)
            handleFrameworkErrors("ObjectBase.Builder.put failed") {
                wrapper.put(name, value)
            }
            return this
        }

        /**
         * Dynamic builder function with type check and alias support.
         * Only used for unit tests, where we need to associate data with an alias.
         */
        @InternalApi
        internal fun put(
            name: String,
            value: Any?,
            alias: String? = null
        ): Builder<T> {
            typeCheck(name, value)
            handleFrameworkErrors("ObjectBase.Builder.put failed") {
                wrapper.put(name, value, alias)
            }
            return this
        }

        private fun typeCheck(
            fieldName: String,
            value: Any?
        ) {
            val fieldDefinition = type.getField(fieldName)
                ?: throw TenantUsageException("Field $fieldName not found on type ${type.name}")
            val fieldContext = DynamicValueBuilderTypeChecker.FieldContext(fieldDefinition, type)
            DynamicValueBuilderTypeChecker(context).checkType(fieldDefinition.type, value, fieldContext)
        }
    }

    companion object {
        // Used to represent null in the field cache, since ConcurrentHashMap does not allow null values
        private const val NULL_VALUE = "OBJECTBASE_GRT_NULL"
    }
}
