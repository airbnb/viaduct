package viaduct.java.api.internal;

import graphql.schema.GraphQLInputObjectType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import viaduct.errors.FrameworkException;
import viaduct.errors.HandleErrors;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Field;
import viaduct.java.api.types.GraphQLInput;
import viaduct.java.api.types.NodeCompositeOutput;

/**
 * Base class for Java input type GRTs (Generated Runtime Types).
 *
 * <p>Mirrors Kotlin's {@code InputLikeBase} pattern — wraps {@code Map<String, Object>} directly
 * rather than copying data into POJOs via reflection.
 *
 * <p>Field access reads directly from the backing map. For nested input types, the map value is
 * wrapped using the provided constructor function (like Kotlin's {@code
 * grtConvFactory.createForInputField()}).
 *
 * <p><b>{@code @oneOf} inputs:</b> the "exactly one field must be set" constraint is enforced at
 * the builder level — a generated {@code build()} on a {@code @oneOf} input calls {@link
 * #validateOneOf} and throws a {@code TenantUsageException} if more or fewer than one field is set.
 * This fails fast so tenants learn of a violation when they build the input rather than only at
 * execution time. graphql-java remains the execution-time backstop, re-validating during input
 * coercion (its {@code ValuesResolverOneOfValidation}) before resolver input GRTs are ever
 * materialized. Unlike Kotlin's {@code InputLikeBase} — whose GRT constructor always receives the
 * schema type and re-runs {@code validateInputData} — this class is handed a null {@code
 * GraphQLInputObjectType} on the nested-input construction path, so there is no separate
 * construction-time {@code @oneOf} check here; the builder plus graphql-java cover every path.
 */
public abstract class InputBase implements GraphQLInput {

  @FunctionalInterface
  protected interface InputConstructor<T extends InputBase> {
    T create(InternalContext context, Map<String, Object> data, GraphQLInputObjectType type);
  }

  @Nullable private final InternalContext __context;
  private final Map<String, Object> inputData;
  @Nullable private final GraphQLInputObjectType graphQLInputObjectType;

  /**
   * Constructs an input GRT with schema type information.
   *
   * <p>Mirrors Kotlin's {@code InputLikeBase(context, inputData, graphQLInputObjectType)}. The
   * {@code graphQLInputObjectType} carries field definitions for schema-aware field access, default
   * value resolution, and input validation.
   *
   * @param __context the per-request InternalContext, propagated to nested input GRTs; may be null
   *     on the builder path
   * @param inputData the backing map of field name to raw value
   * @param graphQLInputObjectType the GraphQL input type definition; may be null on the builder
   *     path (until builders become context-aware)
   */
  protected InputBase(
      @Nullable InternalContext __context,
      Map<String, Object> inputData,
      @Nullable GraphQLInputObjectType graphQLInputObjectType) {
    this.__context = __context;
    this.inputData = inputData;
    this.graphQLInputObjectType = graphQLInputObjectType;
  }

  /**
   * Returns the {@link InternalContext} this input GRT was constructed with, or null on the builder
   * path. Uses double-underscore prefix to mirror Kotlin's naming and avoid generated getter
   * collisions.
   */
  protected @Nullable InternalContext __context() {
    return __context;
  }

  /**
   * Returns the {@link GraphQLInputObjectType} this input GRT was constructed with. Mirrors
   * Kotlin's {@code InputLikeBase.graphQLInputObjectType}. May be null on the builder path.
   */
  protected @Nullable GraphQLInputObjectType getGraphQLInputObjectType() {
    return graphQLInputObjectType;
  }

  /** Returns the backing input data map. Used by the bridge layer to extract data. */
  public Map<String, Object> getInputData() {
    return Collections.unmodifiableMap(inputData);
  }

  /**
   * Returns whether the backing input data contains the field, including when its value is null.
   *
   * <p>Generated input and arguments GRTs expose this through a type-safe {@code isPresent} method.
   * Presence reflects explicit operation input only for top-level fields because graphql-java may
   * apply default values while coercing nested input objects.
   */
  protected final boolean isFieldPresent(Field<?> field) {
    return inputData.containsKey(field.getName());
  }

  /**
   * Validates the {@code @oneOf} constraint for an input type: exactly one field must be present
   * with a non-null value. Throws a {@code TenantUsageException} otherwise. Called from the
   * generated {@code build()} of {@code @oneOf} inputs so violations fail fast at construction
   * time, mirroring the {@code @oneOf} check in Kotlin's {@code InputLikeBase.validateInputData}.
   *
   * @param typeName the input type's GraphQL name, used in the error message
   * @param data the builder's accumulated field data
   */
  public static void validateOneOf(String typeName, Map<String, Object> data) {
    // Mirror graphql-java's ValuesResolverOneOfValidation: first require exactly one supplied key,
    // then require that key's value to be non-null. Counting supplied keys (not non-null values)
    // means {byId: "1", byName: null} is rejected as two keys, matching execution-time coercion.
    if (data.size() != 1) {
      sneakyThrowTenantUsage(
          "Exactly one field must be set for @oneOf type "
              + typeName
              + ", but "
              + data.size()
              + " were: "
              + new ArrayList<>(data.keySet()),
          null);
    }
    Map.Entry<String, Object> only = data.entrySet().iterator().next();
    if (only.getValue() == null) {
      sneakyThrowTenantUsage(
          "Field '"
              + only.getKey()
              + "' for @oneOf type "
              + typeName
              + " must have a non-null value",
          null);
    }
  }

  /** Gets a scalar field value from the input data map. Like Kotlin: {@code get(fieldName)}. */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T> T get(String fieldName) {
    return HandleErrors.framework(
        "InputBase.get: " + fieldName, () -> (T) inputData.get(fieldName));
  }

  /**
   * Gets a scalar field value with temporal coercion. Like Kotlin: {@code get(fieldName)} with
   * coercion for DateTime, Date, and Time scalars.
   *
   * @param fieldName the field name
   * @param scalarType the GraphQL scalar type name ("DateTime", "Date", or "Time")
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T> T get(String fieldName, String scalarType) {
    return HandleErrors.framework(
        "InputBase.get: " + fieldName,
        () -> {
          Object raw = inputData.get(fieldName);
          return (T) ObjectBase.coerceScalar(raw, scalarType);
        });
  }

  /**
   * Gets a scalar list field value from the input data map. Validates the container is a {@link
   * List} at runtime. Like Kotlin: {@code get(fieldName)} for list-typed scalar fields.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T> List<T> getScalarList(String fieldName) {
    return HandleErrors.framework(
        "InputBase.getScalarList: " + fieldName,
        () -> {
          Object value = inputData.get(fieldName);
          if (value == null) {
            return null;
          }
          if (value instanceof List<?>) {
            return (List<T>) value;
          }
          throw new FrameworkException(
              "Expected List for field '" + fieldName + "', got " + value.getClass().getName(),
              null);
        });
  }

  /**
   * Gets a scalar list field value with temporal coercion. Each element in the list is coerced
   * according to the scalar type.
   *
   * @param fieldName the field name
   * @param scalarType the GraphQL scalar type name ("DateTime", "Date", or "Time")
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T> List<T> getScalarList(String fieldName, String scalarType) {
    return HandleErrors.framework(
        "InputBase.getScalarList: " + fieldName,
        () -> {
          Object value = inputData.get(fieldName);
          if (value == null) {
            return null;
          }
          if (value instanceof List<?> list) {
            List<Object> coerced = new ArrayList<>(list.size());
            for (Object element : list) {
              coerced.add(ObjectBase.coerceScalar(element, scalarType));
            }
            return (List<T>) coerced;
          }
          throw new FrameworkException(
              "Expected List for field '" + fieldName + "', got " + value.getClass().getName(),
              null);
        });
  }

  /**
   * Gets a nested input field, wrapping the nested map using the provided constructor and passing
   * this GRT's {@link InternalContext} so it propagates to the nested input GRT. Like Kotlin:
   * {@code get(fieldName)} with grtConvFactory wrapping.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T extends InputBase> T getInput(String fieldName, InputConstructor<T> constructor) {
    return HandleErrors.framework(
        "InputBase.getInput: " + fieldName,
        () -> {
          Object value = inputData.get(fieldName);
          if (value == null) {
            return null;
          }
          if (value instanceof InputBase) {
            return (T) value;
          }
          if (value instanceof Map<?, ?> map) {
            return constructor.create(__context, (Map<String, Object>) map, null);
          }
          return (T) value;
        });
  }

  /**
   * Gets a list of nested input fields, wrapping each element map using the provided constructor.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T extends InputBase> List<T> getInputList(
      String fieldName, InputConstructor<T> constructor) {
    return HandleErrors.framework(
        "InputBase.getInputList: " + fieldName,
        () -> {
          Object value = inputData.get(fieldName);
          if (value == null) {
            return null;
          }
          if (value instanceof List<?> list) {
            List<T> wrapped = new ArrayList<>(list.size());
            for (Object element : list) {
              if (element == null) {
                wrapped.add(null);
              } else if (element instanceof InputBase) {
                wrapped.add((T) element);
              } else if (element instanceof Map<?, ?> map) {
                wrapped.add(constructor.create(__context, (Map<String, Object>) map, null));
              } else {
                wrapped.add((T) element);
              }
            }
            return wrapped;
          }
          return (List<T>) value;
        });
  }

  /**
   * Gets an enum field, converting String to enum if needed. Like Kotlin: {@code get(fieldName)}
   * with enum conversion.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <E extends Enum<E>> E getEnum(String fieldName, Class<E> enumClass) {
    return HandleErrors.framework(
        "InputBase.getEnum: " + fieldName,
        () -> {
          Object value = inputData.get(fieldName);
          if (value == null) {
            return null;
          }
          if (enumClass.isInstance(value)) {
            return (E) value;
          }
          return Enum.valueOf(enumClass, value.toString());
        });
  }

  /** Gets a list of enum fields, converting String values to enums if needed. */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <E extends Enum<E>> List<E> getEnumList(String fieldName, Class<E> enumClass) {
    return HandleErrors.framework(
        "InputBase.getEnumList: " + fieldName,
        () -> {
          Object value = inputData.get(fieldName);
          if (value == null) {
            return null;
          }
          if (value instanceof List<?> list) {
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
          }
          return (List<E>) value;
        });
  }

  /**
   * Gets a GlobalID field from the input data, deserializing the raw string into a typed {@link
   * GlobalID}. Like Kotlin's {@code get(fieldName)} for @idOf-annotated input fields.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T extends NodeCompositeOutput> GlobalID<T> getGlobalID(String fieldName) {
    Object raw = inputData.get(fieldName);
    if (raw == null) {
      return null;
    }
    try {
      return __context().deserializeGlobalID((String) raw);
    } catch (RuntimeException e) {
      sneakyThrowTenantUsage("Invalid GlobalID for field '" + fieldName + "': " + raw, e);
      throw new AssertionError("unreachable");
    }
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
    throw (E) e;
  }

  private static void sneakyThrowTenantUsage(String message, Throwable cause) {
    sneakyThrow(new viaduct.errors.TenantUsageException(message, cause));
  }

  /**
   * Gets a list of GlobalID values from the input data, deserializing each string element into a
   * typed {@link GlobalID}.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T extends NodeCompositeOutput> List<GlobalID<T>> getGlobalIDList(String fieldName) {
    return HandleErrors.framework(
        "InputBase.getGlobalIDList: " + fieldName,
        () -> {
          Object raw = inputData.get(fieldName);
          if (raw == null) {
            return null;
          }
          if (!(raw instanceof List<?> list)) {
            throw new FrameworkException(
                "Expected List for field '" + fieldName + "', got " + raw.getClass().getName(),
                null);
          }
          List<GlobalID<T>> result = new ArrayList<>(list.size());
          for (Object element : list) {
            result.add(element == null ? null : __context().deserializeGlobalID((String) element));
          }
          return result;
        });
  }
}
