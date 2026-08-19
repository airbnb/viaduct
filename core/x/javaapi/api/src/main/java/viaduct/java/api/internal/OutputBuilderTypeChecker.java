package viaduct.java.api.internal;

import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import viaduct.errors.FrameworkException;
import viaduct.errors.HandleErrors;
import viaduct.errors.TenantUsageException;
import viaduct.graphql.Ids;
import viaduct.graphql.schema.GraphqlTypeMappingsKt;
import viaduct.java.api.globalid.GlobalID;

/**
 * Validates values stored by generated Java output builders where JVM type erasure prevents the
 * generated setter signature from enforcing the GraphQL type.
 */
public final class OutputBuilderTypeChecker {

  /** Fixed package containing generated Java GRT classes. */
  public static final String GENERATED_GRT_PACKAGE = "viaduct.java.grts";

  private static final Map<String, Class<?>> SCALAR_CLASSES =
      GraphqlTypeMappingsKt.getBaseGraphqlScalarJavaTypeMapping();

  private OutputBuilderTypeChecker() {}

  /**
   * Validates a generated builder field value against the runtime schema and returns a detached
   * snapshot suitable for storage by the builder.
   *
   * <p>Lists are traversed recursively so erased scalar, enum, object, interface, and union element
   * types are checked. Each list level is copied and made unmodifiable so mutation after a setter
   * call cannot invalidate the checked value.
   */
  @SuppressWarnings("unchecked")
  public static <T> T checkField(
      InternalContext context, String parentTypeName, String fieldName, T value) {
    return checkField(context, parentTypeName, fieldName, null, value);
  }

  /**
   * Validates a generated builder field using the generated class for its unwrapped GraphQL type.
   *
   * <p>The class token keeps generated-code validation exact without resolving classes from a
   * configurable package. Scalar and GlobalID fields pass {@code null}.
   */
  @SuppressWarnings("unchecked")
  public static <T> T checkField(
      InternalContext context,
      String parentTypeName,
      String fieldName,
      Class<?> expectedGeneratedType,
      T value) {
    return (T)
        HandleErrors.framework(
            "OutputBuilderTypeChecker.checkField failed",
            () -> {
              GraphQLObjectType parentType =
                  context.getSchema().getSchema().getObjectType(parentTypeName);
              if (parentType == null) {
                throw new FrameworkException(
                    "GraphQL object type " + parentTypeName + " not found in runtime schema", null);
              }
              GraphQLFieldDefinition field = parentType.getField(fieldName);
              if (field == null) {
                throw new FrameworkException(
                    "Field " + fieldName + " not found on GraphQL object type " + parentTypeName,
                    null);
              }
              return checkType(
                  context, parentType, field, field.getType(), expectedGeneratedType, value);
            });
  }

  private static Object checkType(
      InternalContext context,
      GraphQLObjectType parentType,
      GraphQLFieldDefinition field,
      GraphQLType type,
      Class<?> expectedGeneratedType,
      Object value)
      throws Exception {
    if (value == null) {
      if (GraphQLTypeUtil.isNonNull(type)) {
        throw new TenantUsageException(
            "Got null builder value for non-null type "
                + GraphQLTypeUtil.simplePrint(type)
                + " for field "
                + field.getName(),
            null);
      }
      return null;
    }

    GraphQLType unwrappedType = GraphQLTypeUtil.unwrapNonNull(type);
    if (unwrappedType instanceof GraphQLList listType) {
      if (!(value instanceof List<?> values)) {
        throw new TenantUsageException(
            "Got non-list builder value " + value + " for list type for field " + field.getName(),
            null);
      }
      GraphQLType elementType = GraphQLTypeUtil.unwrapOne(listType);
      List<Object> checkedValues = new ArrayList<>(values.size());
      for (Object element : values) {
        checkedValues.add(
            checkType(context, parentType, field, elementType, expectedGeneratedType, element));
      }
      return Collections.unmodifiableList(checkedValues);
    } else if (unwrappedType instanceof GraphQLScalarType scalarType) {
      checkScalar(context, parentType, field, scalarType, value);
    } else if (unwrappedType instanceof GraphQLEnumType enumType) {
      checkEnum(field, enumType, expectedGeneratedType, value);
    } else if (unwrappedType instanceof GraphQLCompositeType compositeType) {
      checkComposite(context, compositeType, expectedGeneratedType, value);
    }
    return value;
  }

  private static void checkScalar(
      InternalContext context,
      GraphQLObjectType parentType,
      GraphQLFieldDefinition field,
      GraphQLScalarType scalarType,
      Object value)
      throws FrameworkException, TenantUsageException {
    Class<?> expectedClass;
    if ("ID".equals(scalarType.getName())) {
      expectedClass = Ids.isGlobalID(field, parentType) ? GlobalID.class : String.class;
    } else if ("BackingData".equals(scalarType.getName())) {
      expectedClass = Object.class;
    } else {
      expectedClass = SCALAR_CLASSES.get(scalarType.getName());
      if (expectedClass == null) {
        throw new FrameworkException(
            "GraphQL scalar type "
                + scalarType.getName()
                + " mapping to Java type not found for field "
                + field.getName(),
            null);
      }
    }
    if (!expectedClass.isInstance(value)) {
      throw new TenantUsageException(
          "Expected value of type "
              + expectedClass.getSimpleName()
              + " for field "
              + field.getName()
              + ", got "
              + value.getClass().getSimpleName(),
          null);
    }
    if (value instanceof GlobalID<?> globalID) {
      checkGlobalIDTarget(context, parentType, field, globalID);
    }
  }

  private static void checkComposite(
      InternalContext context,
      GraphQLCompositeType expectedType,
      Class<?> expectedGeneratedType,
      Object value)
      throws TenantUsageException {
    GraphQLSchema schema = context.getSchema().getSchema();
    GraphQLObjectType actualType =
        value instanceof ObjectBase objectValue
            ? concreteObjectType(schema, objectValue, expectedGeneratedType)
            : null;
    if (actualType == null || !isValidObjectType(schema, expectedType, actualType)) {
      throw new TenantUsageException(
          "Expected object of type "
              + expectedType.getName()
              + " for builder value, got "
              + value.getClass().getSimpleName(),
          null);
    }
  }

  private static GraphQLObjectType concreteObjectType(
      GraphQLSchema schema, ObjectBase value, Class<?> expectedGeneratedType) {
    if (value.getJavaEngineObjectData() != null) {
      return value.getJavaEngineObjectData().getType();
    }
    if (value.getJavaNodeReference() != null) {
      return value.getJavaNodeReference().getType();
    }
    if (value.getJavaRootFieldReference() != null
        && value.getJavaRootFieldReference().getType() instanceof GraphQLObjectType objectType) {
      return objectType;
    }

    GraphQLObjectType conventionType = schema.getObjectType(value.getClass().getSimpleName());
    if (conventionType != null
        && isGeneratedClassForType(conventionType.getName(), value, expectedGeneratedType)) {
      return conventionType;
    }
    for (GraphQLType schemaType : schema.getAllTypesAsList()) {
      if (schemaType instanceof GraphQLObjectType objectType) {
        if (isGeneratedClassForType(objectType.getName(), value, expectedGeneratedType)) {
          return objectType;
        }
      }
    }
    return null;
  }

  private static boolean isGeneratedClassForType(
      String typeName, Object value, Class<?> expectedGeneratedType) {
    if (expectedGeneratedType != null) {
      return expectedGeneratedType.isInstance(value)
          && value.getClass().getSimpleName().equals(typeName);
    }
    return value.getClass().getName().equals(GENERATED_GRT_PACKAGE + "." + typeName);
  }

  private static boolean isValidObjectType(
      GraphQLSchema schema, GraphQLCompositeType expectedType, GraphQLObjectType actualType) {
    if (expectedType instanceof GraphQLObjectType) {
      return actualType.getName().equals(expectedType.getName());
    }
    if (expectedType instanceof GraphQLInterfaceType || expectedType instanceof GraphQLUnionType) {
      return schema.isPossibleType(expectedType, actualType);
    }
    return false;
  }

  private static void checkGlobalIDTarget(
      InternalContext context,
      GraphQLObjectType parentType,
      GraphQLFieldDefinition field,
      GlobalID<?> value)
      throws FrameworkException, TenantUsageException {
    GraphQLSchema schema = context.getSchema().getSchema();
    String expectedTypeName = Ids.globalIDType(field, parentType);
    String actualTypeName = value.getType().getName();
    GraphQLType expectedType = schema.getType(expectedTypeName);
    GraphQLObjectType actualType = schema.getObjectType(actualTypeName);
    if (expectedType == null) {
      throw new FrameworkException(
          "GlobalID target type "
              + expectedTypeName
              + " not found in runtime schema for field "
              + field.getName(),
          null);
    }
    boolean valid =
        actualType != null
            && (expectedType instanceof GraphQLObjectType
                ? actualTypeName.equals(expectedTypeName)
                : expectedType instanceof GraphQLInterfaceType interfaceType
                    && schema.isPossibleType(interfaceType, actualType));
    if (!valid) {
      throw new TenantUsageException(
          "Expected GlobalID targeting "
              + expectedTypeName
              + " for field "
              + field.getName()
              + ", got GlobalID targeting "
              + actualTypeName,
          null);
    }
  }

  private static void checkEnum(
      GraphQLFieldDefinition field,
      GraphQLEnumType enumType,
      Class<?> expectedGeneratedType,
      Object value)
      throws TenantUsageException {
    String valueName;
    if (value instanceof String stringValue) {
      valueName = stringValue;
    } else {
      if (!isGeneratedClassForType(enumType.getName(), value, expectedGeneratedType)) {
        throw new TenantUsageException(
            "Expected value of type "
                + enumType.getName()
                + " for field "
                + field.getName()
                + ", got "
                + value.getClass().getSimpleName(),
            null);
      }
      valueName = ((Enum<?>) value).name();
    }
    if (enumType.getValue(valueName) == null) {
      throw new TenantUsageException(
          "Invalid enum value '"
              + valueName
              + "' for type "
              + enumType.getName()
              + " for field "
              + field.getName(),
          null);
    }
  }
}
