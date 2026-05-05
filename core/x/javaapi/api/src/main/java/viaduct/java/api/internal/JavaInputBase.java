package viaduct.java.api.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import viaduct.errors.FrameworkException;
import viaduct.errors.HandleErrors;
import viaduct.java.api.types.GraphQLInput;

/**
 * Base class for Java input type GRTs (Generated Runtime Types).
 *
 * <p>Mirrors Kotlin's {@code InputLikeBase} pattern — wraps {@code Map<String, Object>} directly
 * rather than copying data into POJOs via reflection.
 *
 * <p>Field access reads directly from the backing map. For nested input types, the map value is
 * wrapped using the provided constructor function (like Kotlin's {@code
 * grtConvFactory.createForInputField()}).
 */
public abstract class JavaInputBase implements GraphQLInput {

  private final Map<String, Object> inputData;

  protected JavaInputBase(Map<String, Object> inputData) {
    this.inputData = inputData;
  }

  /** Returns the backing input data map. Used by the bridge layer to extract data. */
  public Map<String, Object> getInputData() {
    return Collections.unmodifiableMap(inputData);
  }

  /** Gets a scalar field value from the input data map. Like Kotlin: {@code get(fieldName)}. */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T> T get(String fieldName) {
    return HandleErrors.framework(
        "JavaInputBase.get: " + fieldName, () -> (T) inputData.get(fieldName));
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
        "JavaInputBase.get: " + fieldName,
        () -> {
          Object raw = inputData.get(fieldName);
          return (T) JavaObjectBase.coerceScalar(raw, scalarType);
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
        "JavaInputBase.getScalarList: " + fieldName,
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
        "JavaInputBase.getScalarList: " + fieldName,
        () -> {
          Object value = inputData.get(fieldName);
          if (value == null) {
            return null;
          }
          if (value instanceof List<?> list) {
            List<Object> coerced = new ArrayList<>(list.size());
            for (Object element : list) {
              coerced.add(JavaObjectBase.coerceScalar(element, scalarType));
            }
            return (List<T>) coerced;
          }
          throw new FrameworkException(
              "Expected List for field '" + fieldName + "', got " + value.getClass().getName(),
              null);
        });
  }

  /**
   * Gets a nested input field, wrapping the nested map using the provided constructor. Like Kotlin:
   * {@code get(fieldName)} with grtConvFactory wrapping.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T extends JavaInputBase> T getInput(
      String fieldName, Function<Map<String, Object>, T> constructor) {
    return HandleErrors.framework(
        "JavaInputBase.getInput: " + fieldName,
        () -> {
          Object value = inputData.get(fieldName);
          if (value == null) {
            return null;
          }
          if (value instanceof JavaInputBase) {
            return (T) value;
          }
          if (value instanceof Map<?, ?> map) {
            return constructor.apply((Map<String, Object>) map);
          }
          return (T) value;
        });
  }

  /**
   * Gets a list of nested input fields, wrapping each element map using the provided constructor.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  protected <T extends JavaInputBase> List<T> getInputList(
      String fieldName, Function<Map<String, Object>, T> constructor) {
    return HandleErrors.framework(
        "JavaInputBase.getInputList: " + fieldName,
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
              } else if (element instanceof JavaInputBase) {
                wrapped.add((T) element);
              } else if (element instanceof Map<?, ?> map) {
                wrapped.add(constructor.apply((Map<String, Object>) map));
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
        "JavaInputBase.getEnum: " + fieldName,
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
        "JavaInputBase.getEnumList: " + fieldName,
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
}
