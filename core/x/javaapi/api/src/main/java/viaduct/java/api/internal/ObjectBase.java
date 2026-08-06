package viaduct.java.api.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import viaduct.engine.api.EngineObjectData;
import viaduct.engine.api.NodeReference;
import viaduct.engine.api.RootFieldReference;
import viaduct.errors.FrameworkException;
import viaduct.errors.HandleErrors;
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
    this.nodeReference = null;
    this.rootFieldReference = null;
  }

  /**
   * Builder path constructor: wraps a builder-populated map.
   *
   * <p>Like Kotlin: {@code build() -> buildEngineObjectData() -> constructor}. The builder path has
   * no execution context, so {@code context} is typically null.
   */
  protected ObjectBase(@Nullable InternalContext __context, Map<String, Object> mapData) {
    this.__context = __context;
    this.engineData = null;
    this.mapData = mapData;
    this.nodeReference = null;
    this.rootFieldReference = null;
  }

  /**
   * Node reference path constructor: wraps a NodeReference for deferred node resolution.
   *
   * <p>Used by {@code ctx.nodeRef()} to create a lazy reference that the engine resolves later.
   */
  protected ObjectBase(@Nullable InternalContext __context, NodeReference nodeReference) {
    this.__context = __context;
    this.engineData = null;
    this.mapData = null;
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

  private @Nullable Object getRawValue(String fieldName) throws FrameworkException {
    if (engineData != null) {
      return engineData.getOrNull(fieldName);
    } else if (mapData != null) {
      return mapData.get(fieldName);
    } else if (nodeReference != null) {
      // Mirrors Kotlin ObjectBase: only `id` is accessible on an unresolved NodeReference;
      // any other field is rejected as an unset field.
      if ("id".equals(fieldName)) {
        return nodeReference.getId();
      }
      throw new FrameworkException(
          "Field '"
              + fieldName
              + "' cannot be accessed on an unresolved Node reference created using ctx.nodeRef —"
              + " only `id` is accessible.",
          null);
    } else if (rootFieldReference != null) {
      throw new FrameworkException(
          "Field '"
              + fieldName
              + "' cannot be accessed on an unresolved root field reference created using"
              + " ctx.rootFieldRef.",
          null);
    } else {
      throw new FrameworkException(
          "Cannot access field '" + fieldName + "': ObjectBase has no backing data.", null);
    }
  }

  /**
   * Fetches a scalar field value. Like Kotlin: {@code fetch("fieldName", String::class) ->
   * wrapScalar()}.
   *
   * <p>Uses field cache with NULL_VALUE sentinel (identical to Kotlin ObjectBase.get()).
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T> T fetchScalar(String fieldName) {
    return HandleErrors.framework(
        "ObjectBase.fetchScalar: " + fieldName,
        () -> {
          Object cached = fieldCache.get(fieldName);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (T) cached;
          }
          Object raw = getRawValue(fieldName);
          Object toCache = (raw == null) ? NULL_VALUE : raw;
          Object prev = fieldCache.putIfAbsent(fieldName, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (T) result;
        });
  }

  /**
   * Fetches a scalar field value with temporal coercion. Like Kotlin: {@code fetch("fieldName",
   * Instant::class) -> wrapScalar()} which coerces DateTime strings to Instant, Date strings to
   * LocalDate, and Time strings to OffsetTime.
   *
   * @param fieldName the field name
   * @param scalarType the GraphQL scalar type name ("DateTime", "Date", or "Time")
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T> T fetchScalar(String fieldName, String scalarType) {
    return HandleErrors.framework(
        "ObjectBase.fetchScalar: " + fieldName,
        () -> {
          Object cached = fieldCache.get(fieldName);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (T) cached;
          }
          Object raw = getRawValue(fieldName);
          Object coerced = coerceScalar(raw, scalarType);
          Object toCache = (coerced == null) ? NULL_VALUE : coerced;
          Object prev = fieldCache.putIfAbsent(fieldName, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (T) result;
        });
  }

  /**
   * Fetches a scalar list field. Like Kotlin: {@code fetch("fieldName", ...) -> wrapList() ->
   * wrapScalar()}.
   *
   * <p>Validates that the raw value is a {@link List} at runtime. Uses field cache with NULL_VALUE
   * sentinel (identical to other fetch* methods).
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T> List<T> fetchScalarList(String fieldName) {
    return HandleErrors.framework(
        "ObjectBase.fetchScalarList: " + fieldName,
        () -> {
          Object cached = fieldCache.get(fieldName);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (List<T>) cached;
          }
          Object raw = getRawValue(fieldName);
          Object toCache;
          if (raw == null) {
            toCache = NULL_VALUE;
          } else if (raw instanceof List<?>) {
            toCache = raw;
          } else {
            throw new FrameworkException(
                "Expected List for field '" + fieldName + "', got " + raw.getClass().getName(),
                null);
          }
          Object prev = fieldCache.putIfAbsent(fieldName, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (List<T>) result;
        });
  }

  /**
   * Fetches a scalar list field with temporal coercion. Each element in the list is coerced
   * according to the scalar type.
   *
   * @param fieldName the field name
   * @param scalarType the GraphQL scalar type name ("DateTime", "Date", or "Time")
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T> List<T> fetchScalarList(String fieldName, String scalarType) {
    return HandleErrors.framework(
        "ObjectBase.fetchScalarList: " + fieldName,
        () -> {
          Object cached = fieldCache.get(fieldName);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (List<T>) cached;
          }
          Object raw = getRawValue(fieldName);
          Object toCache;
          if (raw == null) {
            toCache = NULL_VALUE;
          } else if (raw instanceof List<?> list) {
            List<Object> coerced = new ArrayList<>(list.size());
            for (Object element : list) {
              coerced.add(coerceScalar(element, scalarType));
            }
            toCache = coerced;
          } else {
            throw new FrameworkException(
                "Expected List for field '" + fieldName + "', got " + raw.getClass().getName(),
                null);
          }
          Object prev = fieldCache.putIfAbsent(fieldName, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (List<T>) result;
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
  @SuppressWarnings("unchecked")
  protected <T extends ObjectBase> T fetchObject(
      String fieldName, BiFunction<InternalContext, EngineObjectData.Sync, T> constructor) {
    return HandleErrors.framework(
        "ObjectBase.fetchObject: " + fieldName,
        () -> {
          Object cached = fieldCache.get(fieldName);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (T) cached;
          }
          Object raw = getRawValue(fieldName);
          Object toCache;
          if (raw == null) {
            toCache = NULL_VALUE;
          } else if (raw instanceof EngineObjectData.Sync syncData) {
            toCache = constructor.apply(__context, syncData);
          } else {
            // Builder path: value is already a ObjectBase instance
            toCache = raw;
          }
          Object prev = fieldCache.putIfAbsent(fieldName, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (T) result;
        });
  }

  /**
   * Fetches a list of composite objects. Like Kotlin: {@code fetch("fieldName", NestedType::class)
   * -> wrapList() -> wrapObject()}.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T extends ObjectBase> List<T> fetchObjectList(
      String fieldName, BiFunction<InternalContext, EngineObjectData.Sync, T> constructor) {
    return HandleErrors.framework(
        "ObjectBase.fetchObjectList: " + fieldName,
        () -> {
          Object cached = fieldCache.get(fieldName);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (List<T>) cached;
          }
          Object raw = getRawValue(fieldName);
          Object toCache;
          if (raw == null) {
            toCache = NULL_VALUE;
          } else if (raw instanceof List<?> list) {
            List<T> wrapped = new ArrayList<>(list.size());
            for (Object element : list) {
              if (element == null) {
                wrapped.add(null);
              } else if (element instanceof EngineObjectData.Sync syncData) {
                wrapped.add(constructor.apply(__context, syncData));
              } else {
                // Builder path: elements are already ObjectBase instances
                wrapped.add((T) element);
              }
            }
            toCache = wrapped;
          } else {
            toCache = raw;
          }
          Object prev = fieldCache.putIfAbsent(fieldName, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (List<T>) result;
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
  @SuppressWarnings("unchecked")
  protected <T> T fetchAbstractObject(String fieldName, Class<T> interfaceClass) {
    return HandleErrors.framework(
        "ObjectBase.fetchAbstractObject: " + fieldName,
        () -> {
          Object cached = fieldCache.get(fieldName);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (T) cached;
          }
          Object raw = getRawValue(fieldName);
          Object toCache;
          if (raw == null) {
            toCache = NULL_VALUE;
          } else if (raw instanceof EngineObjectData.Sync syncData) {
            toCache = instantiateConcrete(__context, syncData, interfaceClass, fieldName);
          } else {
            // Builder path: value is already a concrete instance implementing the interface
            toCache = raw;
          }
          Object prev = fieldCache.putIfAbsent(fieldName, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (T) result;
        });
  }

  /**
   * Fetches a list of interface- or union-typed objects. Like {@link #fetchAbstractObject} but for
   * list fields.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T> List<T> fetchAbstractObjectList(String fieldName, Class<T> interfaceClass) {
    return HandleErrors.framework(
        "ObjectBase.fetchAbstractObjectList: " + fieldName,
        () -> {
          Object cached = fieldCache.get(fieldName);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (List<T>) cached;
          }
          Object raw = getRawValue(fieldName);
          Object toCache;
          if (raw == null) {
            toCache = NULL_VALUE;
          } else if (raw instanceof List<?> list) {
            List<T> wrapped = new ArrayList<>(list.size());
            for (Object element : list) {
              if (element == null) {
                wrapped.add(null);
              } else if (element instanceof EngineObjectData.Sync syncData) {
                wrapped.add(
                    (T) instantiateConcrete(__context, syncData, interfaceClass, fieldName));
              } else {
                // Builder path: elements are already concrete instances
                wrapped.add((T) element);
              }
            }
            toCache = wrapped;
          } else {
            toCache = raw;
          }
          Object prev = fieldCache.putIfAbsent(fieldName, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (List<T>) result;
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
  @SuppressWarnings("unchecked")
  protected <E extends Enum<E>> E fetchEnum(String fieldName, Class<E> enumClass) {
    return HandleErrors.framework(
        "ObjectBase.fetchEnum: " + fieldName,
        () -> {
          Object cached = fieldCache.get(fieldName);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (E) cached;
          }
          Object raw = getRawValue(fieldName);
          Object toCache;
          if (raw == null) {
            toCache = NULL_VALUE;
          } else if (enumClass.isInstance(raw)) {
            // Builder path: already an enum instance
            toCache = raw;
          } else {
            // Engine path: String name -> enum value (mirrors Kotlin wrapEnum)
            toCache = Enum.valueOf(enumClass, raw.toString());
          }
          Object prev = fieldCache.putIfAbsent(fieldName, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (E) result;
        });
  }

  /**
   * Fetches a list of enum values. Like Kotlin: {@code fetch("fieldName", Status::class) ->
   * wrapList() -> wrapEnum()}.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <E extends Enum<E>> List<E> fetchEnumList(String fieldName, Class<E> enumClass) {
    return HandleErrors.framework(
        "ObjectBase.fetchEnumList: " + fieldName,
        () -> {
          Object cached = fieldCache.get(fieldName);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (List<E>) cached;
          }
          Object raw = getRawValue(fieldName);
          Object toCache;
          if (raw == null) {
            toCache = NULL_VALUE;
          } else if (raw instanceof List<?> list) {
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
            toCache = wrapped;
          } else {
            toCache = raw;
          }
          Object prev = fieldCache.putIfAbsent(fieldName, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (List<E>) result;
        });
  }

  /**
   * Fetches a GlobalID field. Deserializes the raw string value into a typed {@link GlobalID}.
   *
   * <p>Like Kotlin's {@code ObjectBase.wrapScalar()} path for GlobalID fields: decodes the
   * serialized ID using the context's GlobalIDCodec.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T extends NodeCompositeOutput> GlobalID<T> fetchGlobalID(String fieldName) {
    return HandleErrors.framework(
        "ObjectBase.fetchGlobalID: " + fieldName,
        () -> {
          Object cached = fieldCache.get(fieldName);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (GlobalID<T>) cached;
          }
          Object raw = getRawValue(fieldName);
          Object toCache;
          if (raw == null) {
            toCache = NULL_VALUE;
          } else {
            toCache = __context.deserializeGlobalID((String) raw);
          }
          Object prev = fieldCache.putIfAbsent(fieldName, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (GlobalID<T>) result;
        });
  }

  /**
   * Fetches a list of GlobalID values. Each element is deserialized from its string representation
   * into a typed {@link GlobalID}.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T extends NodeCompositeOutput> List<GlobalID<T>> fetchGlobalIDList(String fieldName) {
    return HandleErrors.framework(
        "ObjectBase.fetchGlobalIDList: " + fieldName,
        () -> {
          Object cached = fieldCache.get(fieldName);
          if (cached != null) {
            return cached == NULL_VALUE ? null : (List<GlobalID<T>>) cached;
          }
          Object raw = getRawValue(fieldName);
          Object toCache;
          if (raw == null) {
            toCache = NULL_VALUE;
          } else if (raw instanceof List<?> list) {
            List<GlobalID<T>> decoded = new ArrayList<>(list.size());
            for (Object element : list) {
              decoded.add(element == null ? null : __context.deserializeGlobalID((String) element));
            }
            toCache = decoded;
          } else {
            throw new FrameworkException(
                "Expected List for field '" + fieldName + "', got " + raw.getClass().getName(),
                null);
          }
          Object prev = fieldCache.putIfAbsent(fieldName, toCache);
          Object result = (prev != null) ? prev : toCache;
          return result == NULL_VALUE ? null : (List<GlobalID<T>>) result;
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
