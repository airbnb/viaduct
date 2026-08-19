package viaduct.java.api.internal.connbuildertest;

import graphql.Scalars;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import viaduct.engine.api.ViaductSchema;
import viaduct.java.api.context.ConnectionFieldExecutionContext;
import viaduct.java.api.context.ResolverExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.reflect.RootObjectField;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.Connection;
import viaduct.java.api.types.ConnectionArguments;
import viaduct.java.api.types.GraphQLObject;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.java.api.types.NodeObject;
import viaduct.java.api.types.Query;
import viaduct.service.api.spi.DecodedGlobalID;
import viaduct.service.api.spi.GlobalIDCodec;

/**
 * Context stub implementing both {@link ConnectionFieldExecutionContext} and {@link
 * InternalContext} (as the real bridge-layer context does).
 */
final class FakeConnectionContext extends FakeExecutionContext
    implements ConnectionFieldExecutionContext<
        Query, Query, ConnectionArguments, Connection<?, ?>> {
  @Override
  public ConnectionArguments getArguments() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Query getObjectValue() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Query getQueryValue() {
    throw new UnsupportedOperationException();
  }
}

class FakeExecutionContext implements ResolverExecutionContext, InternalContext {
  private static final GraphQLObjectType TEST_EDGE_TYPE =
      GraphQLObjectType.newObject()
          .name("TestEdge")
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("cursor")
                  .type(Scalars.GraphQLString))
          .build();

  private static final GraphQLObjectType PAGE_INFO_TYPE =
      GraphQLObjectType.newObject()
          .name("PageInfo")
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("startCursor")
                  .type(Scalars.GraphQLString))
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("endCursor")
                  .type(Scalars.GraphQLString))
          .build();

  private static final GraphQLObjectType FAKE_NODE_TYPE =
      GraphQLObjectType.newObject()
          .name("FakeNode")
          .field(GraphQLFieldDefinition.newFieldDefinition().name("id").type(Scalars.GraphQLID))
          .build();

  private static final GraphQLObjectType TEST_CONNECTION_TYPE =
      GraphQLObjectType.newObject()
          .name("TestConnection")
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("edges")
                  .type(GraphQLList.list(TEST_EDGE_TYPE)))
          .field(GraphQLFieldDefinition.newFieldDefinition().name("pageInfo").type(PAGE_INFO_TYPE))
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("ownerIDs")
                  .type(GraphQLList.list(Scalars.GraphQLID))
                  .withAppliedDirective(
                      GraphQLAppliedDirective.newDirective()
                          .name("idOf")
                          .argument(
                              GraphQLAppliedDirectiveArgument.newArgument()
                                  .name("type")
                                  .type(Scalars.GraphQLString)
                                  .valueProgrammatic("FakeNode")
                                  .build())
                          .build()))
          .build();

  private static final GraphQLObjectType QUERY_TYPE =
      GraphQLObjectType.newObject()
          .name("Query")
          .field(
              GraphQLFieldDefinition.newFieldDefinition()
                  .name("connection")
                  .type(TEST_CONNECTION_TYPE))
          .build();

  private static final ViaductSchema SCHEMA =
      new ViaductSchema(
          GraphQLSchema.newSchema()
              .query(QUERY_TYPE)
              .additionalType(TEST_CONNECTION_TYPE)
              .additionalType(TEST_EDGE_TYPE)
              .additionalType(PAGE_INFO_TYPE)
              .additionalType(FAKE_NODE_TYPE)
              .build());

  private static final GlobalIDCodec GLOBAL_ID_CODEC =
      new GlobalIDCodec() {
        @Override
        public String serialize(String typeName, String localID) {
          return typeName + ":" + localID;
        }

        @Override
        public DecodedGlobalID deserialize(String globalID) {
          throw new UnsupportedOperationException();
        }
      };

  @Override
  public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String internalID) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID) {
    throw new UnsupportedOperationException();
  }

  @Override
  public @Nullable Object getRequestContext() {
    return null;
  }

  @Override
  public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <A extends Arguments, T extends GraphQLObject> T rootFieldRef(
      RootObjectField<?, T, A> field, A arguments) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalID) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> CompletableFuture<T> query(
      String selections, Map<String, Object> variables, Class<T> targetClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> CompletableFuture<T> mutation(
      String selections, Map<String, Object> variables, Class<T> targetClass) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ViaductSchema getSchema() {
    return SCHEMA;
  }

  @Override
  public graphql.schema.GraphQLInputObjectType getArgumentsInputType(
      String name, String containingTypeName, String fieldName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public GlobalIDCodec getGlobalIDCodec() {
    return GLOBAL_ID_CODEC;
  }

  @Override
  public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
    throw new UnsupportedOperationException();
  }
}
