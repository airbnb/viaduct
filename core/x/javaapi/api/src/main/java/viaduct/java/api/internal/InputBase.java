package viaduct.java.api.internal;

import graphql.GraphQLContext;
import graphql.execution.ValuesResolver;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLTypeUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import viaduct.errors.FrameworkException;
import viaduct.errors.HandleErrors;
import viaduct.errors.TenantUsageException;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Field;
import viaduct.java.api.types.GraphQLInput;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.tenant.runtime.jvm.InputDataValidator;
import viaduct.tenant.runtime.jvm.InputTypeFactory;

/**
 * Base class for Java input type GRTs (Generated Runtime Types).
 *
 * <p>Mirrors Kotlin's {@code InputLikeBase} pattern — wraps {@code Map<String, Object>} directly
 * rather than copying data into POJOs via reflection.
 *
 * <p>Field access reads from the backing map and resolves schema defaults for omitted fields. For
 * nested input types, the map value is wrapped using the provided constructor function (like
 * Kotlin's {@code grtConvFactory.createForInputField()}).
 *
 * <p>Generated builders validate required fields, nested nullability, and {@code @oneOf}
 * constraints before constructing an input. GraphQL Java still performs execution-time coercion.
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
   *     when no execution context is available
   * @param inputData the backing map of field name to raw value
   * @param graphQLInputObjectType the GraphQL input type definition; may be null when wrapping
   *     nested input data that graphql-java has already coerced
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
   * Returns the {@link InternalContext} this input GRT was constructed with, or null when no
   * execution context was available. Uses double-underscore prefix to mirror Kotlin's naming and
   * avoid generated getter collisions.
   */
  protected @Nullable InternalContext __context() {
    return __context;
  }

  /**
   * Returns the {@link GraphQLInputObjectType} this input GRT was constructed with. Mirrors
   * Kotlin's {@code InputLikeBase.graphQLInputObjectType}. May be null when wrapping nested input
   * data that graphql-java has already coerced.
   */
  protected @Nullable GraphQLInputObjectType getGraphQLInputObjectType() {
    return graphQLInputObjectType;
  }

  /**
   * Returns the raw backing input data map without materializing schema defaults. Used by the
   * bridge layer to extract data before graphql-java coercion.
   */
  public Map<String, Object> getInputData() {
    return Collections.unmodifiableMap(inputData);
  }

  /**
   * Returns whether this input contains a value for {@code field} after GraphQL defaults are
   * applied. Explicit {@code null} counts as present. An omitted field with a schema default is
   * present; an omitted field without a default is absent.
   */
  protected final boolean isFieldPresent(Field<?> field) {
    if (inputData.containsKey(field.getName())) {
      return true;
    }
    var fieldDefinition =
        graphQLInputObjectType == null ? null : graphQLInputObjectType.getField(field.getName());
    return fieldDefinition != null && fieldDefinition.hasSetDefaultValue();
  }

  private Object fieldValue(String fieldName) {
    if (inputData.containsKey(fieldName)) {
      return inputData.get(fieldName);
    }
    var fieldDefinition =
        graphQLInputObjectType == null ? null : graphQLInputObjectType.getField(fieldName);
    if (fieldDefinition == null || !fieldDefinition.hasSetDefaultValue()) {
      return null;
    }
    return ValuesResolver.valueToInternalValue(
        fieldDefinition.getInputFieldDefaultValue(),
        fieldDefinition.getType(),
        GraphQLContext.getDefault(),
        Locale.getDefault());
  }

  public static GraphQLInputObjectType inputType(InternalContext context, String typeName) {
    return HandleErrors.framework(
        "InputBase.inputType: " + typeName,
        () -> InputTypeFactory.inputObjectInputType(typeName, context.getSchema()));
  }

  public static GraphQLInputObjectType argumentsType(
      InternalContext context, String typeName, String containingTypeName, String fieldName) {
    return HandleErrors.framework(
        "InputBase.argumentsType: " + containingTypeName + "." + fieldName,
        () -> context.getArgumentsInputType(typeName, containingTypeName, fieldName));
  }

  public static Map<String, Object> validateInputData(
      String typeName, @Nullable GraphQLInputObjectType type, Map<String, Object> data) {
    return HandleErrors.framework(
        "InputBase.validateInputData: " + typeName,
        () -> {
          if (type == null || !typeName.equals(type.getName())) {
            throw new FrameworkException(
                "Input schema type missing or mismatched for " + typeName, null);
          }
          try {
            return InputDataValidator.validateAndCopy(
                type,
                data,
                value -> value instanceof InputBase input ? input.getInputData() : null);
          } catch (IllegalStateException e) {
            throw new TenantUsageException(
                "Failed to build " + typeName + " (" + e.getMessage() + ")", e);
          }
        });
  }

  public static void validateOneOf(String typeName, Map<String, Object> data) {
    try {
      InputDataValidator.validateOneOf(typeName, data);
    } catch (IllegalStateException e) {
      sneakyThrowTenantUsage(e.getMessage(), e);
    }
  }

  @Nullable
  private GraphQLInputObjectType nestedInputType(String fieldName) {
    var field = graphQLInputObjectType == null ? null : graphQLInputObjectType.getField(fieldName);
    return field == null
        ? null
        : (GraphQLInputObjectType) GraphQLTypeUtil.unwrapAll(field.getType());
  }

  /** Gets a scalar field value from the input data map. Like Kotlin: {@code get(fieldName)}. */
  @Nullable
  @SuppressWarnings({"TypeParameterUnusedInFormals", "unchecked"})
  protected <T> T get(String fieldName) {
    return HandleErrors.framework("InputBase.get: " + fieldName, () -> (T) fieldValue(fieldName));
  }

  /**
   * Gets a scalar field value with temporal coercion. Like Kotlin: {@code get(fieldName)} with
   * coercion for DateTime, Date, and Time scalars.
   *
   * @param fieldName the field name
   * @param scalarType the GraphQL scalar type name ("DateTime", "Date", or "Time")
   */
  @Nullable
  @SuppressWarnings({"TypeParameterUnusedInFormals", "unchecked"})
  protected <T> T get(String fieldName, String scalarType) {
    return HandleErrors.framework(
        "InputBase.get: " + fieldName,
        () -> {
          Object raw = fieldValue(fieldName);
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
          Object value = fieldValue(fieldName);
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
          Object value = fieldValue(fieldName);
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
          Object value = fieldValue(fieldName);
          if (value == null) {
            return null;
          }
          if (value instanceof InputBase) {
            return (T) value;
          }
          if (value instanceof Map<?, ?> map) {
            return constructor.create(
                __context, (Map<String, Object>) map, nestedInputType(fieldName));
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
          Object value = fieldValue(fieldName);
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
                wrapped.add(
                    constructor.create(
                        __context, (Map<String, Object>) map, nestedInputType(fieldName)));
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
          Object value = fieldValue(fieldName);
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
          Object value = fieldValue(fieldName);
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
    Object raw = fieldValue(fieldName);
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
          Object raw = fieldValue(fieldName);
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
