package viaduct.java.api.internal;

import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import viaduct.engine.api.EngineObjectData;
import viaduct.engine.api.NodeReference;
import viaduct.engine.api.RootFieldReference;
import viaduct.errors.FrameworkException;
import viaduct.errors.HandleErrors;
import viaduct.errors.TenantUsageException;
import viaduct.errors.UnsetFieldException;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.types.GraphQLObject;
import viaduct.java.api.types.NodeCompositeOutput;

/**
 * Base class for Java object type GRTs (Generated Runtime Types).
 *
 * <p>Mirrors Kotlin's {@code ObjectBase} pattern — wraps {@link EngineObjectData.Sync} directly
 * rather than copying data into POJOs via reflection.
 *
 * <p>Four construction paths (matching Kotlin ObjectBase):
 *
 * <ul>
 *   <li>Engine path: wraps pre-resolved {@link EngineObjectData.Sync} provided by the engine
 *   <li>Builder path: wraps a {@link Map} populated by the generated Builder
 *   <li>Node reference path: wraps a {@link NodeReference} for deferred node resolution
 *   <li>Root field reference path: wraps a {@link RootFieldReference} for deferred field resolution
 * </ul>
 *
 * <p>Reads are strict on every path: a selection that was never set raises {@link
 * UnsetFieldException}, which is how a tenant reading outside its fragment finds out. The generated
 * {@code getXxxOrNull()} accessors wrap the read in {@link #nullOnDataFailure} instead, which turns
 * data-side failures — but not that tenant bug — into null.
 *
 * <p>Field access is cached using a {@link ConcurrentHashMap} with a {@code NULL_VALUE} sentinel to
 * represent null values (identical to Kotlin's {@code OBJECTBASE_GRT_NULL} pattern).
 */
public abstract class ObjectBase implements GraphQLObject {

  // Used to represent null in the field cache, since ConcurrentHashMap does not allow null values.
  // Mirrors Kotlin's OBJECTBASE_GRT_NULL sentinel.
  private static final Object NULL_VALUE = new Object();

  @Nullable private final InternalContext __context;
  private final EngineObjectData.@Nullable Sync engineData;
  @Nullable private final Map<String, Object> mapData;
  @Nullable private final ObjectBase baseObject;
  @Nullable private final String mapDataTypeName;
  @Nullable private final NodeReference nodeReference;
  @Nullable private final RootFieldReference rootFieldReference;
  private final ConcurrentHashMap<String, Object> fieldCache = new ConcurrentHashMap<>();

  /**
   * Engine path constructor: wraps pre-resolved EngineObjectData.Sync.
   *
   * <p>Like Kotlin: {@code ObjectBase(context, engineObject)}.
   *
   * <p>{@code context} is the per-request {@link InternalContext}, propagated to nested GRTs. It
   * may be null on the builder path (Java builders have no execution context).
   */
  protected ObjectBase(@Nullable InternalContext __context, EngineObjectData.Sync engineData) {
    this.__context = __context;
    this.engineData = engineData;
    this.mapData = null;
    this.baseObject = null;
    this.mapDataTypeName = null;
    this.nodeReference = null;
    this.rootFieldReference = null;
  }

  /**
   * Builder path constructor: wraps a builder-populated map.
   *
   * <p>Like Kotlin: {@code build() -> buildEngineObjectData() -> constructor}. {@code
   * graphQLTypeName} names the GraphQL type this GRT represents; the map carries no type of its
   * own, so it is the only way an unset-field failure on this path can report the type Kotlin
   * reports.
   */
  protected ObjectBase(@Nullable InternalContext __context, Map<String, Object> mapData) {
    this(__context, null, mapData, null);
  }

  protected ObjectBase(
      @Nullable InternalContext __context,
      @Nullable ObjectBase baseObject,
      Map<String, Object> mapData) {
    this(__context, baseObject, mapData, null);
  }

  protected ObjectBase(
      @Nullable InternalContext __context,
      Map<String, Object> mapData,
      @Nullable String graphQLTypeName) {
    this(__context, null, mapData, graphQLTypeName);
  }

  protected ObjectBase(
      @Nullable InternalContext __context,
      @Nullable ObjectBase baseObject,
      Map<String, Object> mapData,
      @Nullable String graphQLTypeName) {
    this.__context = __context;
    this.engineData = null;
    this.mapData = mapData;
    this.baseObject = baseObject;
    this.mapDataTypeName = graphQLTypeName;
    this.nodeReference = null;
    this.rootFieldReference = null;
  }

  /**
   * Node reference path constructor: wraps a NodeReference for deferred node resolution.
   *
   * <p>Used by {@code ctx.ref()} to create a lazy reference that the engine resolves later.
   */
  protected ObjectBase(@Nullable InternalContext __context, NodeReference nodeReference) {
    this.__context = __context;
    this.engineData = null;
    this.mapData = null;
    this.baseObject = null;
    this.mapDataTypeName = null;
    this.nodeReference = nodeReference;
    this.rootFieldReference = null;
  }

  /**
   * Root-field-reference path constructor: wraps a reference for deferred engine resolution.
   *
   * <p>No fields are accessible until the engine resolves the reference.
   */
  protected ObjectBase(@Nullable InternalContext __context, RootFieldReference rootFieldReference) {
    this.__context = __context;
    this.engineData = null;
    this.mapData = null;
    this.baseObject = null;
    this.mapDataTypeName = null;
    this.nodeReference = null;
    this.rootFieldReference = rootFieldReference;
  }

  /**
   * Returns the {@link InternalContext} this GRT was constructed with, or null on the builder path.
   * Uses double-underscore prefix to mirror Kotlin's {@code __context} and avoid generated getter
   * collisions (a GraphQL field named "context" would produce {@code getContext()}).
   */
  protected @Nullable InternalContext __context() {
    return __context;
  }

  /**
   * Returns the backing EngineObjectData.Sync if this GRT was created via the engine path. Used by
   * the bridge layer to extract data without reflection.
   */
  public EngineObjectData.@Nullable Sync getJavaEngineObjectData() {
    return engineData;
  }

  /**
   * Returns the backing NodeReference if this GRT was created via the node reference path. Used by
   * the bridge layer to pass the NodeReference to the engine.
   */
  public @Nullable NodeReference getJavaNodeReference() {
    return nodeReference;
  }

  /** Returns the unresolved root field reference for bridge conversion, if present. */
  public @Nullable RootFieldReference getJavaRootFieldReference() {
    return rootFieldReference;
  }

  /**
   * Returns the backing map if this GRT was created via the builder path. Used by the bridge layer
   * to extract data without reflection.
   */
  @Nullable
  public Map<String, Object> getJavaMapData() {
    return mapData != null ? Collections.unmodifiableMap(mapData) : null;
  }

  public @Nullable ObjectBase getJavaBaseObject() {
    return baseObject;
  }

  protected final ObjectBase toBuilderBase() {
    return HandleErrors.framework(
        "ObjectBase.toBuilder",
        () -> {
          if (nodeReference != null) {
            throw new TenantUsageException(
                "Cannot call toBuilder() on an unresolved NodeReference.", null);
          }
          if (rootFieldReference != null) {
            throw new TenantUsageException(
                "Cannot call toBuilder() on an unresolved RootFieldReference.", null);
          }
          return this;
        });
  }

  /** Dynamically reads a tenant-local {@code @backingData} field using its scalar leaf class. */
  @Nullable
  @SuppressWarnings({"TypeParameterUnusedInFormals", "unchecked"})
  public <T> T get(String fieldName, Class<?> backingDataClass) {
    return HandleErrors.framework(
        "ObjectBase.get: " + fieldName,
        () -> {
          GraphQLFieldsContainer type = getFieldsContainer();
          GraphQLFieldDefinition field = type.getField(fieldName);
          if (field == null) {
            throw new FrameworkException(
                "Field '" + fieldName + "' not found on type " + type.getName(), null);
          }
          checkBackingDataType(fieldName, field.getType());
          Object cached = cachedRawValue(fieldName);
          Object value = cached == NULL_VALUE ? null : cached;
          return (T) checkBackingData(fieldName, field.getType(), backingDataClass, value);
        });
  }

  private GraphQLFieldsContainer getFieldsContainer() throws FrameworkException {
    if (baseObject != null) {
      return baseObject.getFieldsContainer();
    }
    if (engineData != null) {
      return engineData.getType();
    }
    if (nodeReference != null) {
      return nodeReference.getType();
    }
    if (rootFieldReference != null) {
      GraphQLType type = rootFieldReference.getType();
      if (type instanceof GraphQLFieldsContainer fieldsContainer) {
        return fieldsContainer;
      }
    }
    if (__context != null) {
      GraphQLObjectType type =
          __context.getSchema().getSchema().getObjectType(getClass().getSimpleName());
      if (type != null) {
        return type;
      }
    }
    throw new FrameworkException(
        "Cannot determine GraphQL type for dynamic field access on " + getClass().getName(), null);
  }

  /**
   * Runs {@code block}, returning null instead of propagating a data-side failure (an upstream
   * resolver error, or a field whose value is stored as an error). Tenant bugs, framework bugs, and
   * cancellation still propagate. Generated {@code getXxxOrNull()} accessors call this.
   */
  protected static <T> @Nullable T nullOnDataFailure(Callable<T> block) {
    return HandleErrors.dataFailureToNull(block);
  }

  private @Nullable Object getRawValue(String fieldName, @Nullable String alias)
      throws FrameworkException, TenantUsageException {
    String selection = selectionOf(fieldName, alias);
    if (engineData != null) {
      return engineData.get(selection);
    } else if (mapData != null) {
      if (mapData.containsKey(selection)) {
        return mapData.get(selection);
      }
      if (baseObject != null) {
        return baseObject.getRawValue(fieldName, alias);
      }
      throw unsetField(selection, mapDataObjectType(), "no value was set for it on the builder");
    } else if (nodeReference != null) {
      if ("id".equals(selection)) {
        return nodeReference.getId();
      }
      throw unsetField(
          selection,
          nodeReference.getType(),
          "only id can be accessed on an unresolved Node reference created using ctx.ref");
    } else if (rootFieldReference != null) {
      throw unsetField(
          selection,
          rootFieldReference.getType() instanceof GraphQLObjectType type ? type : null,
          "fields cannot be accessed on an unresolved root field reference created using"
              + " ctx.ref");
    } else {
      throw new FrameworkException(
          "Cannot access field '" + selection + "': ObjectBase has no backing data.", null);
    }
  }

  private Object cachedRawValue(String fieldName) throws FrameworkException, TenantUsageException {
    Object cached = fieldCache.get(fieldName);
    if (cached != null) {
      return cached;
    }
    Object raw = getRawValue(fieldName, null);
    Object toCache = raw == null ? NULL_VALUE : raw;
    Object previous = fieldCache.putIfAbsent(fieldName, toCache);
    return previous != null ? previous : toCache;
  }

  private static @Nullable Object checkBackingData(
      String fieldName, GraphQLType type, Class<?> backingDataClass, @Nullable Object value)
      throws FrameworkException, TenantUsageException {
    if (value == null) {
      if (GraphQLTypeUtil.isNonNull(type)) {
        throw new TenantUsageException(
            "Got null backing data value for non-null type "
                + GraphQLTypeUtil.simplePrint(type)
                + " on field '"
                + fieldName
                + "'",
            null);
      }
      return null;
    }

    GraphQLType unwrappedType = GraphQLTypeUtil.unwrapNonNull(type);
    if (unwrappedType instanceof GraphQLList listType) {
      if (!(value instanceof List<?> values)) {
        throw new FrameworkException(
            "Expected List for field '" + fieldName + "', got " + value.getClass().getName(), null);
      }
      for (Object element : values) {
        checkBackingData(fieldName, listType.getWrappedType(), backingDataClass, element);
      }
      return values;
    }

    if (!(unwrappedType instanceof GraphQLScalarType)) {
      throw new FrameworkException(
          "Unexpected backing-data field type " + GraphQLTypeUtil.simplePrint(type), null);
    }
    if (!value.getClass().equals(backingDataClass)) {
      throw new TenantUsageException(
          "Expected backing data value of field '"
              + fieldName
              + "' to be of type "
              + backingDataClass.getSimpleName()
              + ", got "
              + value.getClass().getSimpleName(),
          null);
    }
    return value;
  }

  private static void checkBackingDataType(String fieldName, GraphQLType type)
      throws FrameworkException {
    GraphQLType unwrappedType = GraphQLTypeUtil.unwrapNonNull(type);
    if (unwrappedType instanceof GraphQLList listType) {
      checkBackingDataType(fieldName, listType.getWrappedType());
      return;
    }
    if (!(unwrappedType instanceof GraphQLScalarType scalarType)
        || !"BackingData".equals(scalarType.getName())) {
      throw new FrameworkException(
          "Dynamic backing-data getter cannot read field '"
              + fieldName
              + "' of type "
              + GraphQLTypeUtil.simplePrint(type),
          null);
    }
  }

  private static String selectionOf(String fieldName, @Nullable String alias) {
    return alias != null ? alias : fieldName;
  }

  /**
   * Resolves the GraphQL type of a builder-path GRT, or null when neither the type name nor the
   * request context is available (hand-written GRTs in tests supply neither).
   */
  private @Nullable GraphQLObjectType mapDataObjectType() {
    if (mapDataTypeName != null
        && __context != null
        && __context.getSchema().getSchema().getType(mapDataTypeName)
            instanceof GraphQLObjectType type) {
      return type;
    }
    return baseObject != null ? baseObject.backingObjectType() : null;
  }

  private @Nullable GraphQLObjectType backingObjectType() {
    if (engineData != null) {
      return engineData.getType();
    }
    if (mapData != null) {
      return mapDataObjectType();
    }
    if (nodeReference != null) {
      return nodeReference.getType();
    }
    return rootFieldReference != null
            && rootFieldReference.getType() instanceof GraphQLObjectType type
        ? type
        : null;
  }

  private void validateValue(String fieldName, @Nullable Object value)
      throws FrameworkException, TenantUsageException {
    GraphQLObjectType objectType = backingObjectType();
    if (objectType == null) {
      return;
    }
    GraphQLFieldDefinition fieldDefinition = objectType.getFieldDefinition(fieldName);
    if (fieldDefinition != null) {
      validateValue(fieldDefinition.getType(), value);
    }
  }

  private void validateValue(GraphQLType type, @Nullable Object value)
      throws FrameworkException, TenantUsageException {
    if (value == null) {
      if (!GraphQLTypeUtil.isNonNull(type)) {
        return;
      }
      throw new TenantUsageException(
          "Got null value for non-null type " + GraphQLTypeUtil.simplePrint(type), null);
    }
    GraphQLType unwrappedType = GraphQLTypeUtil.unwrapNonNull(type);
    if (unwrappedType instanceof GraphQLList listType && value instanceof List<?> values) {
      for (Object element : values) {
        validateValue(listType.getWrappedType(), element);
      }
    } else if (unwrappedType instanceof GraphQLCompositeType compositeType) {
      validateCompositeValue(compositeType, value);
    }
  }

  private void validateCompositeValue(GraphQLCompositeType expectedType, Object value)
      throws FrameworkException {
    GraphQLObjectType actualType = null;
    if (value instanceof EngineObjectData.Sync syncData) {
      actualType = syncData.getType();
    } else if (value instanceof ObjectBase objectValue) {
      actualType = objectValue.backingObjectType();
    }
    if (actualType == null) {
      return;
    }

    boolean valid;
    if (expectedType instanceof GraphQLObjectType objectType) {
      valid = objectType.getName().equals(actualType.getName());
    } else if (__context != null) {
      valid = __context.getSchema().getSchema().isPossibleType(expectedType, actualType);
    } else if (expectedType instanceof GraphQLInterfaceType interfaceType) {
      valid =
          actualType.getInterfaces().stream()
              .anyMatch(type -> type.getName().equals(interfaceType.getName()));
    } else if (expectedType instanceof GraphQLUnionType unionType) {
      valid = unionType.isPossibleType(actualType);
    } else {
      throw new FrameworkException(
          "Unexpected composite type " + GraphQLTypeUtil.simplePrint(expectedType), null);
    }
    if (!valid) {
      throw new IllegalArgumentException(
          "Expected value with GraphQL type "
              + expectedType.getName()
              + ", got "
              + actualType.getName());
    }
  }

  /**
   * Builds the failure for reading a selection that was never set. Degrades to a plain {@link
   * TenantUsageException} when the containing type is unavailable, since {@link
   * UnsetFieldException} requires one; both are tenant-attributed, so read behavior is unaffected
   * either way.
   */
  private static TenantUsageException unsetField(
      String selection, @Nullable GraphQLObjectType objectType, String details) {
    if (objectType != null) {
      return new UnsetFieldException(selection, objectType, details);
    }
    return new TenantUsageException(
        "Attempted to access field " + selection + " but it was not set: " + details, null);
  }

  /** Transforms a non-null raw engine or builder value into the value a {@code fetch*} returns. */
  @FunctionalInterface
  private interface ValueWrapper {
    @Nullable Object wrap(Object raw) throws Exception;
  }

  /**
   * Reads {@code fieldName} (under {@code alias}, when the caller selected one), runs it through
   * {@code wrapper}, and memoizes the result. Mirrors the caching in Kotlin {@code ObjectBase.get}:
   * keyed by selection, with a sentinel standing in for null.
   */
  @Nullable
  @SuppressWarnings({"TypeParameterUnusedInFormals", "unchecked"})
  private <T> T fetchCached(
      String method, String fieldName, @Nullable String alias, ValueWrapper wrapper) {
    String selection = selectionOf(fieldName, alias);
    return HandleErrors.accessor(
        "ObjectBase." + method + ": " + selection,
        () -> {
          Object cached = fieldCache.get(selection);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (T) cached;
          }
          Object raw = getRawValue(fieldName, alias);
          validateValue(fieldName, raw);
          Object wrapped = (raw == null) ? null : wrapper.wrap(raw);
          Object toCache = (wrapped == null) ? NULL_VALUE : wrapped;
          Object prev = fieldCache.putIfAbsent(selection, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (T) result;
        });
  }

  private static List<?> requireList(Object raw) throws TenantUsageException {
    if (raw instanceof List<?> list) {
      return list;
    }
    throw new TenantUsageException("Got non-list value " + raw + " for list type", null);
  }

  /**
   * Fetches a scalar field value. Like Kotlin: {@code fetch("fieldName", String::class) ->
   * wrapScalar()}.
   */
  @Nullable
  @SuppressWarnings("TypeParameterUnusedInFormals")
  protected <T> T fetchScalar(String fieldName, @Nullable String alias) {
    return fetchCached("fetchScalar", fieldName, alias, raw -> raw);
  }

  /**
   * Fetches a scalar field value with temporal coercion. Like Kotlin: {@code fetch("fieldName",
   * Instant::class) -> wrapScalar()} which coerces DateTime strings to Instant, Date strings to
   * LocalDate, and Time strings to OffsetTime.
   *
   * @param scalarType the GraphQL scalar type name ("DateTime", "Date", or "Time")
   */
  @Nullable
  @SuppressWarnings("TypeParameterUnusedInFormals")
  protected <T> T fetchScalar(String fieldName, @Nullable String alias, String scalarType) {
    return fetchCached("fetchScalar", fieldName, alias, raw -> coerceScalar(raw, scalarType));
  }

  /**
   * Fetches a scalar list field. Like Kotlin: {@code fetch("fieldName", ...) -> wrapList() ->
   * wrapScalar()}.
   */
  @Nullable
  protected <T> List<T> fetchScalarList(String fieldName, @Nullable String alias) {
    return fetchCached("fetchScalarList", fieldName, alias, ObjectBase::requireList);
  }

  /**
   * Fetches a scalar list field with temporal coercion. Each element in the list is coerced
   * according to the scalar type.
   *
   * @param scalarType the GraphQL scalar type name ("DateTime", "Date", or "Time")
   */
  @Nullable
  protected <T> List<T> fetchScalarList(
      String fieldName, @Nullable String alias, String scalarType) {
    return fetchCached(
        "fetchScalarList",
        fieldName,
        alias,
        raw -> {
          List<?> list = requireList(raw);
          List<Object> coerced = new ArrayList<>(list.size());
          for (Object element : list) {
            coerced.add(coerceScalar(element, scalarType));
          }
          return coerced;
        });
  }

  /**
   * Fetches a composite object field. Like Kotlin: {@code fetch("fieldName", NestedType::class) ->
   * wrapObject()}.
   *
   * <p>If the raw value is {@link EngineObjectData.Sync}, wraps it using the provided constructor,
   * passing this GRT's {@link InternalContext} so it propagates to the nested GRT. If already a
   * {@link ObjectBase} (builder path), returns as-is.
   */
  @Nullable
  protected <T extends ObjectBase> T fetchObject(
      String fieldName,
      @Nullable String alias,
      Class<T> objectClass,
      BiFunction<InternalContext, EngineObjectData.Sync, T> constructor) {
    return fetchCached(
        "fetchObject",
        fieldName,
        alias,
        raw -> {
          if (raw instanceof EngineObjectData.Sync syncData) {
            return constructor.apply(__context, syncData);
          }
          if (objectClass.isInstance(raw)) {
            return objectClass.cast(raw);
          }
          if (raw instanceof ObjectBase) {
            throw new IllegalArgumentException(
                "Expected value of type "
                    + objectClass.getSimpleName()
                    + ", got "
                    + raw.getClass().getSimpleName());
          }
          throw new TenantUsageException(
              "Expected value to be an instance of EngineObjectData, got " + raw, null);
        });
  }

  /**
   * Fetches a list of composite objects. Like Kotlin: {@code fetch("fieldName", NestedType::class)
   * -> wrapList() -> wrapObject()}.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T extends ObjectBase> List<T> fetchObjectList(
      String fieldName,
      @Nullable String alias,
      Class<T> objectClass,
      BiFunction<InternalContext, EngineObjectData.Sync, T> constructor) {
    return fetchCached(
        "fetchObjectList",
        fieldName,
        alias,
        raw -> {
          List<?> list = requireList(raw);
          List<T> wrapped = new ArrayList<>(list.size());
          for (Object element : list) {
            if (element == null) {
              wrapped.add(null);
            } else if (element instanceof EngineObjectData.Sync syncData) {
              wrapped.add(constructor.apply(__context, syncData));
            } else if (objectClass.isInstance(element)) {
              wrapped.add(objectClass.cast(element));
            } else if (element instanceof ObjectBase) {
              throw new IllegalArgumentException(
                  "Expected value of type "
                      + objectClass.getSimpleName()
                      + ", got "
                      + element.getClass().getSimpleName());
            } else {
              throw new TenantUsageException(
                  "Expected value to be an instance of EngineObjectData, got " + element, null);
            }
          }
          return wrapped;
        });
  }

  /**
   * Fetches an interface- or union-typed field whose concrete type is determined at runtime.
   *
   * <p>Like Kotlin's {@code ObjectBase.wrapObject()}: reads the concrete GraphQL type name from
   * {@link EngineObjectData.Sync#getType()}, loads the corresponding Java class from the same
   * package as {@code interfaceClass}, and constructs it via the {@link EngineObjectData.Sync}
   * constructor.
   *
   * <p>Builder path: the value is expected to already be a concrete instance implementing the
   * interface, so it is returned as-is.
   */
  @Nullable
  protected <T> T fetchAbstractObject(
      String fieldName, @Nullable String alias, Class<T> interfaceClass) {
    return fetchCached(
        "fetchAbstractObject",
        fieldName,
        alias,
        raw -> {
          if (raw instanceof EngineObjectData.Sync syncData) {
            return instantiateConcrete(__context, syncData, interfaceClass, fieldName);
          }
          if (interfaceClass.isInstance(raw)) {
            return raw;
          }
          throw new TenantUsageException(
              "Expected value to be an instance of EngineObjectData, got " + raw, null);
        });
  }

  /**
   * Fetches a list of interface- or union-typed objects. Like {@link #fetchAbstractObject} but for
   * list fields.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T> List<T> fetchAbstractObjectList(
      String fieldName, @Nullable String alias, Class<T> interfaceClass) {
    return fetchCached(
        "fetchAbstractObjectList",
        fieldName,
        alias,
        raw -> {
          List<?> list = requireList(raw);
          List<T> wrapped = new ArrayList<>(list.size());
          for (Object element : list) {
            if (element == null) {
              wrapped.add(null);
            } else if (element instanceof EngineObjectData.Sync syncData) {
              wrapped.add((T) instantiateConcrete(__context, syncData, interfaceClass, fieldName));
            } else if (interfaceClass.isInstance(element)) {
              wrapped.add((T) element);
            } else {
              throw new TenantUsageException(
                  "Expected value to be an instance of EngineObjectData, got " + element, null);
            }
          }
          return wrapped;
        });
  }

  /**
   * Instantiates the concrete Java class for an interface/union-typed field from engine data.
   *
   * <p>Uses the GraphQL type name from the engine data to find the concrete class in the same
   * package as the interface class, then calls its {@code (InternalContext, EngineObjectData.Sync)}
   * constructor, passing the context so it propagates to the nested GRT.
   */
  private static Object instantiateConcrete(
      @Nullable InternalContext context,
      EngineObjectData.Sync syncData,
      Class<?> interfaceClass,
      String fieldName)
      throws FrameworkException {
    String concreteTypeName = syncData.getType().getName();
    String fqcn = interfaceClass.getPackageName() + "." + concreteTypeName;
    try {
      Class<?> concreteClass = Class.forName(fqcn);
      return concreteClass
          .getDeclaredConstructor(InternalContext.class, EngineObjectData.Sync.class)
          .newInstance(context, syncData);
    } catch (ReflectiveOperationException e) {
      throw new FrameworkException(
          "Failed to instantiate concrete type '"
              + concreteTypeName
              + "' for interface field '"
              + fieldName
              + "'",
          e);
    }
  }

  /**
   * Fetches an enum field. Like Kotlin: {@code fetch("fieldName", Status::class) -> wrapEnum()}.
   *
   * <p>Converts String to enum using {@link Enum#valueOf} (matching Kotlin's {@code wrapEnum}
   * behavior). If the value is already an enum instance (builder path), returns as-is.
   */
  @Nullable
  protected <E extends Enum<E>> E fetchEnum(
      String fieldName, @Nullable String alias, Class<E> enumClass) {
    return fetchCached(
        "fetchEnum",
        fieldName,
        alias,
        raw -> enumClass.isInstance(raw) ? raw : Enum.valueOf(enumClass, raw.toString()));
  }

  /**
   * Fetches a list of enum values. Like Kotlin: {@code fetch("fieldName", Status::class) ->
   * wrapList() -> wrapEnum()}.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <E extends Enum<E>> List<E> fetchEnumList(
      String fieldName, @Nullable String alias, Class<E> enumClass) {
    return fetchCached(
        "fetchEnumList",
        fieldName,
        alias,
        raw -> {
          List<?> list = requireList(raw);
          List<E> wrapped = new ArrayList<>(list.size());
          for (Object element : list) {
            if (element == null) {
              wrapped.add(null);
            } else if (enumClass.isInstance(element)) {
              wrapped.add((E) element);
            } else {
              wrapped.add(Enum.valueOf(enumClass, element.toString()));
            }
          }
          return wrapped;
        });
  }

  /**
   * Fetches a GlobalID field. Deserializes the raw string value into a typed {@link GlobalID}.
   *
   * <p>Like Kotlin's {@code ObjectBase.wrapScalar()} path for GlobalID fields: decodes the
   * serialized ID using the context's GlobalIDCodec.
   */
  @Nullable
  protected <T extends NodeCompositeOutput> GlobalID<T> fetchGlobalID(
      String fieldName, @Nullable String alias) {
    return fetchCached(
        "fetchGlobalID", fieldName, alias, raw -> __context.deserializeGlobalID((String) raw));
  }

  /**
   * Fetches a list of GlobalID values. Each element is deserialized from its string representation
   * into a typed {@link GlobalID}.
   */
  @Nullable
  protected <T extends NodeCompositeOutput> List<GlobalID<T>> fetchGlobalIDList(
      String fieldName, @Nullable String alias) {
    return fetchCached(
        "fetchGlobalIDList",
        fieldName,
        alias,
        raw -> {
          List<?> list = requireList(raw);
          List<GlobalID<T>> decoded = new ArrayList<>(list.size());
          for (Object element : list) {
            decoded.add(element == null ? null : __context.deserializeGlobalID((String) element));
          }
          return decoded;
        });
  }

  // ===== Scalar coercion helpers (mirrors Kotlin ObjectBase.wrapScalar) =====

  /**
   * Coerces a raw scalar value to the expected Java type based on the GraphQL scalar type name.
   * Mirrors Kotlin's {@code ObjectBase.wrapScalar()} behavior for DateTime, Date, and Time scalars.
   *
   * @param raw the raw value from the engine or builder
   * @param scalarType the GraphQL scalar type name, or null for no coercion
   * @return the coerced value
   */
  // Accessed by InputBase (same package) for input argument coercion.
  // Wrapped so direct callers (and tests) don't need to declare `throws FrameworkException`;
  // intentional FrameworkException throws from coerceTo* helpers pass through unchanged.
  static Object coerceScalar(@Nullable Object raw, @Nullable String scalarType) {
    return HandleErrors.framework(
        "ObjectBase.coerceScalar: " + scalarType,
        () -> {
          if (raw == null || scalarType == null) {
            return raw;
          }
          return switch (scalarType) {
            case "DateTime" -> coerceToInstant(raw);
            case "Date" -> coerceToLocalDate(raw);
            case "Time" -> coerceToOffsetTime(raw);
            default -> raw;
          };
        });
  }

  /**
   * Coerces a value to {@link Instant}. Mirrors Kotlin ObjectBase.wrapScalar() for DateTime:
   * Instant pass-through, String parsed as ISO_OFFSET_DATE_TIME and converted to Instant.
   */
  private static Instant coerceToInstant(Object value) throws FrameworkException {
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof String s) {
      return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
    }
    throw new FrameworkException("Could not convert " + value + " to Instant.", null);
  }

  /**
   * Coerces a value to {@link LocalDate}. LocalDate pass-through, String parsed via
   * LocalDate.parse().
   */
  private static LocalDate coerceToLocalDate(Object value) throws FrameworkException {
    if (value instanceof LocalDate date) {
      return date;
    }
    if (value instanceof String s) {
      return LocalDate.parse(s);
    }
    throw new FrameworkException("Could not convert " + value + " to LocalDate.", null);
  }

  /**
   * Coerces a value to {@link OffsetTime}. OffsetTime pass-through, String parsed via
   * OffsetTime.parse().
   */
  private static OffsetTime coerceToOffsetTime(Object value) throws FrameworkException {
    if (value instanceof OffsetTime time) {
      return time;
    }
    if (value instanceof String s) {
      return OffsetTime.parse(s);
    }
    throw new FrameworkException("Could not convert " + value + " to OffsetTime.", null);
  }
}
