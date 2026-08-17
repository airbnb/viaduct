package viaduct.java.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import graphql.Scalars;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLUnionType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import viaduct.engine.api.ViaductSchema;
import viaduct.errors.TenantUsageException;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.GRT;
import viaduct.java.api.types.GraphQLEnum;
import viaduct.java.api.types.GraphQLInterface;
import viaduct.java.api.types.GraphQLObject;
import viaduct.java.api.types.GraphQLUnion;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.service.api.spi.GlobalIDCodec;

class OutputBuilderTypeCheckerTest {

  private static final GraphQLInterfaceType NAMED_TYPE =
      GraphQLInterfaceType.newInterface()
          .name("Named")
          .field(
              GraphQLFieldDefinition.newFieldDefinition().name("name").type(Scalars.GraphQLString))
          .build();

  private static final GraphQLObjectType TAG_TYPE =
      GraphQLObjectType.newObject()
          .name("Tag")
          .withInterface(NAMED_TYPE)
          .field(
              GraphQLFieldDefinition.newFieldDefinition().name("name").type(Scalars.GraphQLString))
          .build();

  private static final GraphQLObjectType ITEM_TYPE =
      GraphQLObjectType.newObject()
          .name("Item")
          .field(
              GraphQLFieldDefinition.newFieldDefinition().name("name").type(Scalars.GraphQLString))
          .build();

  private static final GraphQLObjectType OTHER_TYPE =
      GraphQLObjectType.newObject()
          .name("Other")
          .field(
              GraphQLFieldDefinition.newFieldDefinition().name("name").type(Scalars.GraphQLString))
          .build();

  private static final GraphQLObjectType STALE_TYPE =
      GraphQLObjectType.newObject()
          .name("Stale")
          .field(
              GraphQLFieldDefinition.newFieldDefinition().name("name").type(Scalars.GraphQLString))
          .build();

  private static final GraphQLUnionType SEARCH_RESULT_TYPE =
      GraphQLUnionType.newUnionType()
          .name("SearchResult")
          .possibleType(TAG_TYPE)
          .possibleType(ITEM_TYPE)
          .build();

  private static final GraphQLEnumType STATUS_TYPE =
      GraphQLEnumType.newEnum().name("Status").value("ACTIVE").value("FUTURE").build();

  private static final GraphQLObjectType CONTAINER_TYPE =
      GraphQLObjectType.newObject()
          .name("Container")
          .field(
              GraphQLFieldDefinition.newFieldDefinition().name("plainID").type(Scalars.GraphQLID))
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("tags")
                  .type(GraphQLList.list(TAG_TYPE)))
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("nestedTags")
                  .type(GraphQLList.list(GraphQLList.list(GraphQLNonNull.nonNull(TAG_TYPE)))))
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("namedValues")
                  .type(GraphQLList.list(NAMED_TYPE)))
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("searchResults")
                  .type(GraphQLList.list(SEARCH_RESULT_TYPE)))
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("scores")
                  .type(GraphQLList.list(Scalars.GraphQLInt)))
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("statuses")
                  .type(GraphQLList.list(STATUS_TYPE)))
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("nodeIDs")
                  .type(GraphQLList.list(Scalars.GraphQLID))
                  .withAppliedDirective(idOf("Node")))
          .build();

  private static final GraphQLInterfaceType NODE_TYPE =
      GraphQLInterfaceType.newInterface()
          .name("Node")
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("id")
                  .type(GraphQLNonNull.nonNull(Scalars.GraphQLID)))
          .build();

  private static final GraphQLObjectType NODE_VALUE_TYPE =
      GraphQLObjectType.newObject()
          .name("NodeValue")
          .withInterface(NODE_TYPE)
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("id")
                  .type(GraphQLNonNull.nonNull(Scalars.GraphQLID)))
          .build();

  private static final GraphQLObjectType OTHER_NODE_VALUE_TYPE =
      GraphQLObjectType.newObject()
          .name("OtherNodeValue")
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("id")
                  .type(GraphQLNonNull.nonNull(Scalars.GraphQLID)))
          .build();

  private static final GraphQLObjectType QUERY_TYPE =
      GraphQLObjectType.newObject()
          .name("Query")
          .field(GraphQLFieldDefinition.newFieldDefinition().name("container").type(CONTAINER_TYPE))
          .field(
              GraphQLFieldDefinition.newFieldDefinition().name("nodeValue").type(NODE_VALUE_TYPE))
          .build();

  private static final GraphQLSchema GRAPHQL_SCHEMA =
      GraphQLSchema.newSchema()
          .query(QUERY_TYPE)
          .codeRegistry(
              GraphQLCodeRegistry.newCodeRegistry()
                  .typeResolver(NAMED_TYPE, environment -> TAG_TYPE)
                  .typeResolver(NODE_TYPE, environment -> NODE_VALUE_TYPE)
                  .typeResolver(SEARCH_RESULT_TYPE, environment -> TAG_TYPE)
                  .build())
          .additionalType(CONTAINER_TYPE)
          .additionalType(NODE_TYPE)
          .additionalType(NODE_VALUE_TYPE)
          .additionalType(OTHER_NODE_VALUE_TYPE)
          .additionalType(TAG_TYPE)
          .additionalType(ITEM_TYPE)
          .additionalType(OTHER_TYPE)
          .additionalType(STALE_TYPE)
          .additionalType(NAMED_TYPE)
          .additionalType(SEARCH_RESULT_TYPE)
          .additionalType(STATUS_TYPE)
          .build();

  private static final InternalContext CONTEXT = new FakeContext();

  @Test
  void distinguishesPlainIDsFromNodeGlobalIDs() {
    GlobalID<NodeValue> globalID = new FakeGlobalID<>(NodeValue.class);

    assertThatCode(
            () -> OutputBuilderTypeChecker.checkField(CONTEXT, "Container", "plainID", "123"))
        .doesNotThrowAnyException();
    assertThatCode(() -> OutputBuilderTypeChecker.checkField(CONTEXT, "NodeValue", "id", globalID))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> OutputBuilderTypeChecker.checkField(CONTEXT, "Container", "plainID", globalID))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage("Expected value of type String for field plainID, got FakeGlobalID");
    assertThatThrownBy(() -> OutputBuilderTypeChecker.checkField(CONTEXT, "NodeValue", "id", "123"))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage("Expected value of type GlobalID for field id, got String");
  }

  @Test
  void validatesGlobalIDTargetsAgainstRuntimeSchema() {
    GlobalID<NodeValue> nodeValueID = new FakeGlobalID<>(NodeValue.class);
    GlobalID<OtherNodeValue> otherNodeValueID = new FakeGlobalID<>(OtherNodeValue.class);

    assertThatCode(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "nodeIDs", List.of(nodeValueID)))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> OutputBuilderTypeChecker.checkField(CONTEXT, "NodeValue", "id", otherNodeValueID))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage(
            "Expected GlobalID targeting NodeValue for field id, got GlobalID targeting"
                + " OtherNodeValue");
    assertThatThrownBy(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "nodeIDs", List.of(otherNodeValueID)))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage(
            "Expected GlobalID targeting Node for field nodeIDs, got GlobalID targeting"
                + " OtherNodeValue");
  }

  @Test
  void acceptsMatchingObjectInterfaceAndUnionValues() {
    TagValue tag = new TagValue();

    assertThatCode(
            () -> OutputBuilderTypeChecker.checkField(CONTEXT, "Container", "tags", List.of(tag)))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "namedValues", List.of(tag)))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "searchResults", List.of(tag, new ItemValue())))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsWrongObjectTypeInsideNestedList() {
    assertThatThrownBy(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "nestedTags", List.of(List.of(new OtherValue()))))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage("Expected object of type Tag for builder value, got OtherValue");
  }

  @Test
  void rejectsNonObjectValueForCompositeListElement() {
    assertThatThrownBy(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "tags", List.of("not-a-tag")))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage("Expected object of type Tag for builder value, got String");
  }

  @Test
  void rejectsGeneratedInterfaceImplementationThatIsNotAnObjectGRT() {
    assertThatThrownBy(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "namedValues", List.of(new CustomNamedValue())))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage("Expected object of type Named for builder value, got CustomNamedValue");
  }

  @Test
  void rejectsObjectBaseWhoseNameDoesNotMatchTheGeneratedClass() {
    assertThatThrownBy(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "tags", List.of(new Tag())))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage("Expected object of type Tag for builder value, got Tag");
  }

  @Test
  void rejectsCompiledAbstractRelationshipsMissingFromRuntimeSchema() {
    assertThatThrownBy(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "namedValues", List.of(new StaleValue())))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage("Expected object of type Named for builder value, got StaleValue");
    assertThatThrownBy(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "searchResults", List.of(new StaleValue())))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage("Expected object of type SearchResult for builder value, got StaleValue");
  }

  @Test
  void rejectsNullForNonNullCompositeListElement() {
    assertThatThrownBy(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "nestedTags", List.of(Collections.singletonList(null))))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage("Got null builder value for non-null type Tag! for field nestedTags");
  }

  @Test
  void rejectsWrongScalarTypeInsideList() {
    assertThatThrownBy(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "scores", List.of("not-an-int")))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage("Expected value of type Integer for field scores, got String");
  }

  @Test
  void rejectsWrongEnumClassInsideList() {
    assertThatThrownBy(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "statuses", List.of(OtherStatus.ACTIVE)))
        .isInstanceOf(TenantUsageException.class)
        .hasMessage("Expected value of type Status for field statuses, got OtherStatus");
  }

  @Test
  void acceptsRuntimeEnumValueMissingFromCompiledEnum() {
    assertThatCode(
            () ->
                OutputBuilderTypeChecker.checkField(
                    CONTEXT, "Container", "statuses", List.of("FUTURE")))
        .doesNotThrowAnyException();
  }

  @Test
  void returnsRecursivelyDetachedListSnapshot() {
    List<Object> inner = new ArrayList<>();
    List<List<Object>> source = new ArrayList<>();
    source.add(inner);

    List<List<Object>> snapshot =
        OutputBuilderTypeChecker.checkField(CONTEXT, "Container", "nestedTags", source);

    inner.add(new OtherValue());
    source.add(List.of(new OtherValue()));

    assertThat(snapshot).containsExactly(Collections.emptyList());
    assertThatThrownBy(() -> snapshot.add(List.of()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> snapshot.get(0).add(new TagValue()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private interface NamedValue extends GraphQLInterface {}

  private interface SearchResultValue extends GraphQLUnion {}

  private static final class TagValue extends ObjectBase
      implements GraphQLObject, NamedValue, SearchResultValue {
    private TagValue() {
      super(null, Map.of());
    }
  }

  private static final class ItemValue extends ObjectBase
      implements GraphQLObject, SearchResultValue {
    private ItemValue() {
      super(null, Map.of());
    }
  }

  private static final class OtherValue extends ObjectBase implements GraphQLObject {
    private OtherValue() {
      super(null, Map.of());
    }
  }

  private static final class Tag extends ObjectBase implements GraphQLObject {
    private Tag() {
      super(null, Map.of());
    }
  }

  private static final class StaleValue extends ObjectBase
      implements GraphQLObject, NamedValue, SearchResultValue {
    private StaleValue() {
      super(null, Map.of());
    }
  }

  private static final class CustomNamedValue implements NamedValue {}

  private static final class NodeValue implements NodeCompositeOutput {}

  private static final class OtherNodeValue implements NodeCompositeOutput {}

  private static final class FakeGlobalID<T extends NodeCompositeOutput> implements GlobalID<T> {
    private final Class<T> type;

    private FakeGlobalID(Class<T> type) {
      this.type = type;
    }

    @Override
    public Type<T> getType() {
      return Type.ofClass(type);
    }

    @Override
    public String getInternalID() {
      return "123";
    }
  }

  private enum StatusValue implements GraphQLEnum {
    ACTIVE
  }

  private enum OtherStatus {
    ACTIVE
  }

  private static GraphQLAppliedDirective idOf(String typeName) {
    return GraphQLAppliedDirective.newDirective()
        .name("idOf")
        .argument(
            GraphQLAppliedDirectiveArgument.newArgument()
                .name("type")
                .type(Scalars.GraphQLString)
                .valueProgrammatic(typeName)
                .build())
        .build();
  }

  private static final class FakeContext implements InternalContext {
    private final ResolverClassFinder classFinder = new FakeClassFinder();

    @Override
    public ViaductSchema getSchema() {
      return new ViaductSchema(GRAPHQL_SCHEMA);
    }

    @Override
    public graphql.schema.GraphQLInputObjectType getArgumentsInputType(
        String name, String containingTypeName, String fieldName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public GlobalIDCodec getGlobalIDCodec() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResolverClassFinder getClassFinder() {
      return classFinder;
    }

    @Override
    public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FakeClassFinder implements ResolverClassFinder {
    private static final Map<String, Class<? extends GRT>> GRT_CLASSES =
        Map.of(
            "Tag", TagValue.class,
            "Item", ItemValue.class,
            "Other", OtherValue.class,
            "Stale", StaleValue.class,
            "Named", NamedValue.class,
            "SearchResult", SearchResultValue.class,
            "Status", StatusValue.class);

    @Override
    public Set<Class<?>> resolverClassesInPackage() {
      return Set.of();
    }

    @Override
    public Set<Class<?>> nodeResolverForClassesInPackage() {
      return Set.of();
    }

    @Override
    public <T> Set<Class<? extends T>> getSubTypesOf(Class<T> type) {
      return Set.of();
    }

    @Override
    public Class<? extends GRT> grtClassForName(String typeName) throws ClassNotFoundException {
      Class<? extends GRT> result = GRT_CLASSES.get(typeName);
      if (result == null) {
        throw new ClassNotFoundException(typeName);
      }
      return result;
    }

    @Override
    public Class<? extends Arguments> argumentClassForName(String className)
        throws ClassNotFoundException {
      throw new ClassNotFoundException(className);
    }
  }
}
