package com.example.tenant.resolverbases;

import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import graphql.schema.GraphQLInputObjectType;
import viaduct.engine.api.ViaductSchema;
import viaduct.java.api.annotations.ResolverFor;
import viaduct.java.api.context.ConnectionFieldExecutionContext;
import viaduct.java.api.context.FieldExecutionContext;
import viaduct.java.api.context.SelectiveFieldExecutionContext;
import viaduct.java.api.documents.MutationFromAnnotation;
import viaduct.java.api.documents.QueryFromAnnotation;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.BaseBatchedFieldResolver;
import viaduct.java.api.internal.BaseUnbatchedFieldResolver;
import viaduct.java.api.reflect.RootObjectField;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.resolvers.ConnectionResolverBase;
import viaduct.java.api.resolvers.FieldResolverBase;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.GraphQLObject;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.java.api.types.NodeObject;
import viaduct.service.api.spi.GlobalIDCodec;
import com.example.grts.*;

/**
 * Generated resolver base classes for Query type.
 */
@SuppressWarnings({"JavaLangClash", "SameNameButDifferent"})
public final class QueryResolvers {

    private QueryResolvers() {
        // Utility class
    }

        @ResolverFor(typeName = "Query", fieldName = "order", isSelective = false, isBatching = false)
        public abstract static class Order
            implements FieldResolverBase<com.example.grts.Order, com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Order_Arguments, com.example.grts.Order>, BaseUnbatchedFieldResolver {

            /**
             * Context for Query.order resolver.
             * Provides type-safe access to object value, query value, arguments, and selections.
             */
            public static final class Context
                implements FieldResolverBase.Context<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Order_Arguments, com.example.grts.Order>, InternalContext {

                private final FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Order_Arguments, com.example.grts.Order> inner;

                public Context(FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Order_Arguments, com.example.grts.Order> inner) {
                    this.inner = inner;
                }

                @Override
                public com.example.grts.Query getObjectValue() {
                    return inner.getObjectValue();
                }

                @Override
                public com.example.grts.Query getQueryValue() {
                    return inner.getQueryValue();
                }

                @Override
                public com.example.grts.Query_Order_Arguments getArguments() {
                    return inner.getArguments();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String internalID) {
                    return inner.globalIDFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID) {
                    return inner.serialize(globalID);
                }

                @Override
                public Object getRequestContext() {
                    return inner.getRequestContext();
                }

                @Override
                public <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalID) {
                    return inner.globalIDStringFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id) {
                    return inner.nodeRef(id);
                }

                @Override
                public <A extends Arguments, T extends GraphQLObject> T rootFieldRef(
                        RootObjectField<?, T, A> field, A arguments) {
                    return inner.rootFieldRef(field, arguments);
                }

                @Override
                public <T> CompletableFuture<T> query(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> query(QueryFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(operation, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(MutationFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(operation, variables, targetClass);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections) {
                    return inner.query(selections, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections, Map<String, Object> variables) {
                    return inner.query(selections, variables, com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation) {
                    return inner.query(operation, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation, Map<String, Object> variables) {
                    return inner.query(operation, variables, com.example.grts.Query.class);
                }
                public CompletableFuture<com.example.grts.Mutation> mutation(String selections) {
                    return inner.mutation(selections, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(String selections, Map<String, Object> variables) {
                    return inner.mutation(selections, variables, com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation) {
                    return inner.mutation(operation, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation, Map<String, Object> variables) {
                    return inner.mutation(operation, variables, com.example.grts.Mutation.class);
                }

                @Override
                public ViaductSchema getSchema() {
                    return InternalContext.from(inner).getSchema();
                }

                @Override
                public GraphQLInputObjectType getArgumentsInputType(
                        String name, String containingTypeName, String fieldName) {
                    return InternalContext.from(inner)
                            .getArgumentsInputType(name, containingTypeName, fieldName);
                }

                @Override
                public GlobalIDCodec getGlobalIDCodec() {
                    return InternalContext.from(inner).getGlobalIDCodec();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
                    return InternalContext.from(inner).deserializeGlobalID(serialized);
                }
            }

            /**
             * Resolves the order field value for a single parent object.
             * Override this method to implement single-item resolution.
             *
             * @param ctx the execution context
             * @return a future that completes with the resolved value
             */
            public abstract CompletableFuture<com.example.grts.Order> resolve(Context ctx);

            @Override
            @SuppressWarnings("unchecked")
            public final CompletableFuture<?> invokeFieldResolver(
                FieldExecutionContext<?, ?, ?, ?> context) {
                return resolve(new Context((FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Order_Arguments, com.example.grts.Order>) context));
            }
        }

        @ResolverFor(typeName = "Query", fieldName = "topUser", isSelective = true, isBatching = false)
        public abstract static class TopUser
            implements FieldResolverBase<com.example.grts.User, com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.User>, BaseUnbatchedFieldResolver {

            /**
             * Context for Query.topUser resolver.
             * Provides type-safe access to object value, query value, arguments, and selections.
             */
            public static final class Context
                implements FieldResolverBase.Context<com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.User>, SelectiveFieldExecutionContext<com.example.grts.User>, InternalContext {

                private final FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.User> inner;

                public Context(FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.User> inner) {
                    this.inner = inner;
                }

                @Override
                public com.example.grts.Query getObjectValue() {
                    return inner.getObjectValue();
                }

                @Override
                public com.example.grts.Query getQueryValue() {
                    return inner.getQueryValue();
                }

                @Override
                public Arguments.NoArguments getArguments() {
                    return inner.getArguments();
                }

                @Override
                public Object getSelections() {
                    return ((SelectiveFieldExecutionContext<com.example.grts.User>) inner).getSelections();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String internalID) {
                    return inner.globalIDFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID) {
                    return inner.serialize(globalID);
                }

                @Override
                public Object getRequestContext() {
                    return inner.getRequestContext();
                }

                @Override
                public <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalID) {
                    return inner.globalIDStringFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id) {
                    return inner.nodeRef(id);
                }

                @Override
                public <A extends Arguments, T extends GraphQLObject> T rootFieldRef(
                        RootObjectField<?, T, A> field, A arguments) {
                    return inner.rootFieldRef(field, arguments);
                }

                @Override
                public <T> CompletableFuture<T> query(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> query(QueryFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(operation, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(MutationFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(operation, variables, targetClass);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections) {
                    return inner.query(selections, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections, Map<String, Object> variables) {
                    return inner.query(selections, variables, com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation) {
                    return inner.query(operation, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation, Map<String, Object> variables) {
                    return inner.query(operation, variables, com.example.grts.Query.class);
                }
                public CompletableFuture<com.example.grts.Mutation> mutation(String selections) {
                    return inner.mutation(selections, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(String selections, Map<String, Object> variables) {
                    return inner.mutation(selections, variables, com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation) {
                    return inner.mutation(operation, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation, Map<String, Object> variables) {
                    return inner.mutation(operation, variables, com.example.grts.Mutation.class);
                }

                @Override
                public ViaductSchema getSchema() {
                    return InternalContext.from(inner).getSchema();
                }

                @Override
                public GraphQLInputObjectType getArgumentsInputType(
                        String name, String containingTypeName, String fieldName) {
                    return InternalContext.from(inner)
                            .getArgumentsInputType(name, containingTypeName, fieldName);
                }

                @Override
                public GlobalIDCodec getGlobalIDCodec() {
                    return InternalContext.from(inner).getGlobalIDCodec();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
                    return InternalContext.from(inner).deserializeGlobalID(serialized);
                }
            }

            /**
             * Resolves the topUser field value for a single parent object.
             * Override this method to implement single-item resolution.
             *
             * @param ctx the execution context
             * @return a future that completes with the resolved value
             */
            public abstract CompletableFuture<com.example.grts.User> resolve(Context ctx);

            @Override
            @SuppressWarnings("unchecked")
            public final CompletableFuture<?> invokeFieldResolver(
                FieldExecutionContext<?, ?, ?, ?> context) {
                return resolve(new Context((FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.User>) context));
            }
        }

        @ResolverFor(typeName = "Query", fieldName = "popularOrders", isSelective = false, isBatching = true)
        public abstract static class PopularOrders
            implements FieldResolverBase<List<com.example.grts.Order>, com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.Order>, BaseBatchedFieldResolver {

            /**
             * Context for Query.popularOrders resolver.
             * Provides type-safe access to object value, query value, arguments, and selections.
             */
            public static final class Context
                implements FieldResolverBase.Context<com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.Order>, InternalContext {

                private final FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.Order> inner;

                public Context(FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.Order> inner) {
                    this.inner = inner;
                }

                @Override
                public com.example.grts.Query getObjectValue() {
                    return inner.getObjectValue();
                }

                @Override
                public com.example.grts.Query getQueryValue() {
                    return inner.getQueryValue();
                }

                @Override
                public Arguments.NoArguments getArguments() {
                    return inner.getArguments();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String internalID) {
                    return inner.globalIDFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID) {
                    return inner.serialize(globalID);
                }

                @Override
                public Object getRequestContext() {
                    return inner.getRequestContext();
                }

                @Override
                public <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalID) {
                    return inner.globalIDStringFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id) {
                    return inner.nodeRef(id);
                }

                @Override
                public <A extends Arguments, T extends GraphQLObject> T rootFieldRef(
                        RootObjectField<?, T, A> field, A arguments) {
                    return inner.rootFieldRef(field, arguments);
                }

                @Override
                public <T> CompletableFuture<T> query(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> query(QueryFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(operation, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(MutationFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(operation, variables, targetClass);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections) {
                    return inner.query(selections, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections, Map<String, Object> variables) {
                    return inner.query(selections, variables, com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation) {
                    return inner.query(operation, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation, Map<String, Object> variables) {
                    return inner.query(operation, variables, com.example.grts.Query.class);
                }
                public CompletableFuture<com.example.grts.Mutation> mutation(String selections) {
                    return inner.mutation(selections, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(String selections, Map<String, Object> variables) {
                    return inner.mutation(selections, variables, com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation) {
                    return inner.mutation(operation, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation, Map<String, Object> variables) {
                    return inner.mutation(operation, variables, com.example.grts.Mutation.class);
                }

                @Override
                public ViaductSchema getSchema() {
                    return InternalContext.from(inner).getSchema();
                }

                @Override
                public GraphQLInputObjectType getArgumentsInputType(
                        String name, String containingTypeName, String fieldName) {
                    return InternalContext.from(inner)
                            .getArgumentsInputType(name, containingTypeName, fieldName);
                }

                @Override
                public GlobalIDCodec getGlobalIDCodec() {
                    return InternalContext.from(inner).getGlobalIDCodec();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
                    return InternalContext.from(inner).deserializeGlobalID(serialized);
                }
            }

            /**
             * Resolves the popularOrders field value for a batch of parent objects.
             * Override this method to implement batch resolution.
             *
             * @param contexts the list of execution contexts (one per parent object)
             * @return a future that completes with a map from Context to resolved value
             */
            public abstract CompletableFuture<Map<Context, List<com.example.grts.Order>>> batchResolve(List<Context> contexts);

            @Override
            @SuppressWarnings("unchecked")
            public final CompletableFuture<Map<FieldExecutionContext<?, ?, ?, ?>, Object>> invokeFieldBatchResolver(
                List<FieldExecutionContext<?, ?, ?, ?>> contexts) {
                IdentityHashMap<Context, FieldExecutionContext<?, ?, ?, ?>> wrappedToOriginal =
                    new IdentityHashMap<>();
                List<Context> wrappedContexts =
                    contexts.stream()
                        .map(
                            context -> {
                                Context wrapped =
                                    new Context((FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.Order>) context);
                                wrappedToOriginal.put(wrapped, context);
                                return wrapped;
                            })
                        .toList();

                return batchResolve(wrappedContexts)
                    .thenCompose(
                        results -> {
                            IdentityHashMap<FieldExecutionContext<?, ?, ?, ?>, Object> translatedResults =
                                new IdentityHashMap<>();
                            for (var result : results.entrySet()) {
                                Context wrappedContext = result.getKey();
                                FieldExecutionContext<?, ?, ?, ?> originalContext =
                                    wrappedToOriginal.get(wrappedContext);
                                if (originalContext == null) {
                                    return BaseBatchedFieldResolver.failedForUnknownContext(
                                        wrappedContext);
                                }
                                translatedResults.put(originalContext, result.getValue());
                            }
                            return CompletableFuture.completedFuture(translatedResults);
                        });
            }
        }

        @ResolverFor(typeName = "Query", fieldName = "trendingUsers", isSelective = true, isBatching = true)
        public abstract static class TrendingUsers
            implements FieldResolverBase<List<com.example.grts.User>, com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.User>, BaseBatchedFieldResolver {

            /**
             * Context for Query.trendingUsers resolver.
             * Provides type-safe access to object value, query value, arguments, and selections.
             */
            public static final class Context
                implements FieldResolverBase.Context<com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.User>, SelectiveFieldExecutionContext<com.example.grts.User>, InternalContext {

                private final FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.User> inner;

                public Context(FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.User> inner) {
                    this.inner = inner;
                }

                @Override
                public com.example.grts.Query getObjectValue() {
                    return inner.getObjectValue();
                }

                @Override
                public com.example.grts.Query getQueryValue() {
                    return inner.getQueryValue();
                }

                @Override
                public Arguments.NoArguments getArguments() {
                    return inner.getArguments();
                }

                @Override
                public Object getSelections() {
                    return ((SelectiveFieldExecutionContext<com.example.grts.User>) inner).getSelections();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String internalID) {
                    return inner.globalIDFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID) {
                    return inner.serialize(globalID);
                }

                @Override
                public Object getRequestContext() {
                    return inner.getRequestContext();
                }

                @Override
                public <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalID) {
                    return inner.globalIDStringFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id) {
                    return inner.nodeRef(id);
                }

                @Override
                public <A extends Arguments, T extends GraphQLObject> T rootFieldRef(
                        RootObjectField<?, T, A> field, A arguments) {
                    return inner.rootFieldRef(field, arguments);
                }

                @Override
                public <T> CompletableFuture<T> query(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> query(QueryFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(operation, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(MutationFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(operation, variables, targetClass);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections) {
                    return inner.query(selections, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections, Map<String, Object> variables) {
                    return inner.query(selections, variables, com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation) {
                    return inner.query(operation, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation, Map<String, Object> variables) {
                    return inner.query(operation, variables, com.example.grts.Query.class);
                }
                public CompletableFuture<com.example.grts.Mutation> mutation(String selections) {
                    return inner.mutation(selections, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(String selections, Map<String, Object> variables) {
                    return inner.mutation(selections, variables, com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation) {
                    return inner.mutation(operation, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation, Map<String, Object> variables) {
                    return inner.mutation(operation, variables, com.example.grts.Mutation.class);
                }

                @Override
                public ViaductSchema getSchema() {
                    return InternalContext.from(inner).getSchema();
                }

                @Override
                public GraphQLInputObjectType getArgumentsInputType(
                        String name, String containingTypeName, String fieldName) {
                    return InternalContext.from(inner)
                            .getArgumentsInputType(name, containingTypeName, fieldName);
                }

                @Override
                public GlobalIDCodec getGlobalIDCodec() {
                    return InternalContext.from(inner).getGlobalIDCodec();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
                    return InternalContext.from(inner).deserializeGlobalID(serialized);
                }
            }

            /**
             * Resolves the trendingUsers field value for a batch of parent objects.
             * Override this method to implement batch resolution.
             *
             * @param contexts the list of execution contexts (one per parent object)
             * @return a future that completes with a map from Context to resolved value
             */
            public abstract CompletableFuture<Map<Context, List<com.example.grts.User>>> batchResolve(List<Context> contexts);

            @Override
            @SuppressWarnings("unchecked")
            public final CompletableFuture<Map<FieldExecutionContext<?, ?, ?, ?>, Object>> invokeFieldBatchResolver(
                List<FieldExecutionContext<?, ?, ?, ?>> contexts) {
                IdentityHashMap<Context, FieldExecutionContext<?, ?, ?, ?>> wrappedToOriginal =
                    new IdentityHashMap<>();
                List<Context> wrappedContexts =
                    contexts.stream()
                        .map(
                            context -> {
                                Context wrapped =
                                    new Context((FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, Arguments.NoArguments, com.example.grts.User>) context);
                                wrappedToOriginal.put(wrapped, context);
                                return wrapped;
                            })
                        .toList();

                return batchResolve(wrappedContexts)
                    .thenCompose(
                        results -> {
                            IdentityHashMap<FieldExecutionContext<?, ?, ?, ?>, Object> translatedResults =
                                new IdentityHashMap<>();
                            for (var result : results.entrySet()) {
                                Context wrappedContext = result.getKey();
                                FieldExecutionContext<?, ?, ?, ?> originalContext =
                                    wrappedToOriginal.get(wrappedContext);
                                if (originalContext == null) {
                                    return BaseBatchedFieldResolver.failedForUnknownContext(
                                        wrappedContext);
                                }
                                translatedResults.put(originalContext, result.getValue());
                            }
                            return CompletableFuture.completedFuture(translatedResults);
                        });
            }
        }

        @ResolverFor(typeName = "Query", fieldName = "ordersConnection", isSelective = false, isBatching = false)
        public abstract static class OrdersConnection
            implements ConnectionResolverBase<com.example.grts.OrderConnection, com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_OrdersConnection_Arguments, com.example.grts.OrderConnection>, BaseUnbatchedFieldResolver {

            /**
             * Context for Query.ordersConnection resolver.
             * Provides type-safe access to object value, query value, arguments, and selections.
             */
            public static final class Context
                implements ConnectionResolverBase.Context<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_OrdersConnection_Arguments, com.example.grts.OrderConnection>, InternalContext {

                private final FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_OrdersConnection_Arguments, com.example.grts.OrderConnection> inner;

                public Context(FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_OrdersConnection_Arguments, com.example.grts.OrderConnection> inner) {
                    this.inner = inner;
                }

                @Override
                public com.example.grts.Query getObjectValue() {
                    return inner.getObjectValue();
                }

                @Override
                public com.example.grts.Query getQueryValue() {
                    return inner.getQueryValue();
                }

                @Override
                public com.example.grts.Query_OrdersConnection_Arguments getArguments() {
                    return inner.getArguments();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String internalID) {
                    return inner.globalIDFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID) {
                    return inner.serialize(globalID);
                }

                @Override
                public Object getRequestContext() {
                    return inner.getRequestContext();
                }

                @Override
                public <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalID) {
                    return inner.globalIDStringFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id) {
                    return inner.nodeRef(id);
                }

                @Override
                public <A extends Arguments, T extends GraphQLObject> T rootFieldRef(
                        RootObjectField<?, T, A> field, A arguments) {
                    return inner.rootFieldRef(field, arguments);
                }

                @Override
                public <T> CompletableFuture<T> query(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> query(QueryFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(operation, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(MutationFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(operation, variables, targetClass);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections) {
                    return inner.query(selections, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections, Map<String, Object> variables) {
                    return inner.query(selections, variables, com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation) {
                    return inner.query(operation, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation, Map<String, Object> variables) {
                    return inner.query(operation, variables, com.example.grts.Query.class);
                }
                public CompletableFuture<com.example.grts.Mutation> mutation(String selections) {
                    return inner.mutation(selections, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(String selections, Map<String, Object> variables) {
                    return inner.mutation(selections, variables, com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation) {
                    return inner.mutation(operation, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation, Map<String, Object> variables) {
                    return inner.mutation(operation, variables, com.example.grts.Mutation.class);
                }

                @Override
                public ViaductSchema getSchema() {
                    return InternalContext.from(inner).getSchema();
                }

                @Override
                public GraphQLInputObjectType getArgumentsInputType(
                        String name, String containingTypeName, String fieldName) {
                    return InternalContext.from(inner)
                            .getArgumentsInputType(name, containingTypeName, fieldName);
                }

                @Override
                public GlobalIDCodec getGlobalIDCodec() {
                    return InternalContext.from(inner).getGlobalIDCodec();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
                    return InternalContext.from(inner).deserializeGlobalID(serialized);
                }
            }

            /**
             * Resolves the ordersConnection field value for a single parent object.
             * Override this method to implement single-item resolution.
             *
             * @param ctx the execution context
             * @return a future that completes with the resolved value
             */
            public abstract CompletableFuture<com.example.grts.OrderConnection> resolve(Context ctx);

            @Override
            @SuppressWarnings("unchecked")
            public final CompletableFuture<?> invokeFieldResolver(
                FieldExecutionContext<?, ?, ?, ?> context) {
                return resolve(new Context((FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_OrdersConnection_Arguments, com.example.grts.OrderConnection>) context));
            }
        }

        @ResolverFor(typeName = "Query", fieldName = "lookupOrder", isSelective = false, isBatching = false)
        public abstract static class LookupOrder
            implements FieldResolverBase<com.example.grts.Order, com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_LookupOrder_Arguments, com.example.grts.Order>, BaseUnbatchedFieldResolver {

            /**
             * Context for Query.lookupOrder resolver.
             * Provides type-safe access to object value, query value, arguments, and selections.
             */
            public static final class Context
                implements FieldResolverBase.Context<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_LookupOrder_Arguments, com.example.grts.Order>, InternalContext {

                private final FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_LookupOrder_Arguments, com.example.grts.Order> inner;

                public Context(FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_LookupOrder_Arguments, com.example.grts.Order> inner) {
                    this.inner = inner;
                }

                @Override
                public com.example.grts.Query getObjectValue() {
                    return inner.getObjectValue();
                }

                @Override
                public com.example.grts.Query getQueryValue() {
                    return inner.getQueryValue();
                }

                @Override
                public com.example.grts.Query_LookupOrder_Arguments getArguments() {
                    return inner.getArguments();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String internalID) {
                    return inner.globalIDFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID) {
                    return inner.serialize(globalID);
                }

                @Override
                public Object getRequestContext() {
                    return inner.getRequestContext();
                }

                @Override
                public <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalID) {
                    return inner.globalIDStringFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id) {
                    return inner.nodeRef(id);
                }

                @Override
                public <A extends Arguments, T extends GraphQLObject> T rootFieldRef(
                        RootObjectField<?, T, A> field, A arguments) {
                    return inner.rootFieldRef(field, arguments);
                }

                @Override
                public <T> CompletableFuture<T> query(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> query(QueryFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(operation, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(MutationFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(operation, variables, targetClass);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections) {
                    return inner.query(selections, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections, Map<String, Object> variables) {
                    return inner.query(selections, variables, com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation) {
                    return inner.query(operation, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation, Map<String, Object> variables) {
                    return inner.query(operation, variables, com.example.grts.Query.class);
                }
                public CompletableFuture<com.example.grts.Mutation> mutation(String selections) {
                    return inner.mutation(selections, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(String selections, Map<String, Object> variables) {
                    return inner.mutation(selections, variables, com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation) {
                    return inner.mutation(operation, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation, Map<String, Object> variables) {
                    return inner.mutation(operation, variables, com.example.grts.Mutation.class);
                }

                @Override
                public ViaductSchema getSchema() {
                    return InternalContext.from(inner).getSchema();
                }

                @Override
                public GraphQLInputObjectType getArgumentsInputType(
                        String name, String containingTypeName, String fieldName) {
                    return InternalContext.from(inner)
                            .getArgumentsInputType(name, containingTypeName, fieldName);
                }

                @Override
                public GlobalIDCodec getGlobalIDCodec() {
                    return InternalContext.from(inner).getGlobalIDCodec();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
                    return InternalContext.from(inner).deserializeGlobalID(serialized);
                }
            }

            /**
             * Resolves the lookupOrder field value for a single parent object.
             * Override this method to implement single-item resolution.
             *
             * @param ctx the execution context
             * @return a future that completes with the resolved value
             */
            public abstract CompletableFuture<com.example.grts.Order> resolve(Context ctx);

            @Override
            @SuppressWarnings("unchecked")
            public final CompletableFuture<?> invokeFieldResolver(
                FieldExecutionContext<?, ?, ?, ?> context) {
                return resolve(new Context((FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_LookupOrder_Arguments, com.example.grts.Order>) context));
            }
        }

        @ResolverFor(typeName = "Query", fieldName = "node", isSelective = false, isBatching = false)
        public abstract static class Node
            implements FieldResolverBase<com.example.grts.Node, com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Node_Arguments, com.example.grts.Node>, BaseUnbatchedFieldResolver {

            /**
             * Context for Query.node resolver.
             * Provides type-safe access to object value, query value, arguments, and selections.
             */
            public static final class Context
                implements FieldResolverBase.Context<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Node_Arguments, com.example.grts.Node>, InternalContext {

                private final FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Node_Arguments, com.example.grts.Node> inner;

                public Context(FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Node_Arguments, com.example.grts.Node> inner) {
                    this.inner = inner;
                }

                @Override
                public com.example.grts.Query getObjectValue() {
                    return inner.getObjectValue();
                }

                @Override
                public com.example.grts.Query getQueryValue() {
                    return inner.getQueryValue();
                }

                @Override
                public com.example.grts.Query_Node_Arguments getArguments() {
                    return inner.getArguments();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String internalID) {
                    return inner.globalIDFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID) {
                    return inner.serialize(globalID);
                }

                @Override
                public Object getRequestContext() {
                    return inner.getRequestContext();
                }

                @Override
                public <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalID) {
                    return inner.globalIDStringFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id) {
                    return inner.nodeRef(id);
                }

                @Override
                public <A extends Arguments, T extends GraphQLObject> T rootFieldRef(
                        RootObjectField<?, T, A> field, A arguments) {
                    return inner.rootFieldRef(field, arguments);
                }

                @Override
                public <T> CompletableFuture<T> query(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> query(QueryFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(operation, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(MutationFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(operation, variables, targetClass);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections) {
                    return inner.query(selections, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections, Map<String, Object> variables) {
                    return inner.query(selections, variables, com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation) {
                    return inner.query(operation, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation, Map<String, Object> variables) {
                    return inner.query(operation, variables, com.example.grts.Query.class);
                }
                public CompletableFuture<com.example.grts.Mutation> mutation(String selections) {
                    return inner.mutation(selections, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(String selections, Map<String, Object> variables) {
                    return inner.mutation(selections, variables, com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation) {
                    return inner.mutation(operation, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation, Map<String, Object> variables) {
                    return inner.mutation(operation, variables, com.example.grts.Mutation.class);
                }

                @Override
                public ViaductSchema getSchema() {
                    return InternalContext.from(inner).getSchema();
                }

                @Override
                public GraphQLInputObjectType getArgumentsInputType(
                        String name, String containingTypeName, String fieldName) {
                    return InternalContext.from(inner)
                            .getArgumentsInputType(name, containingTypeName, fieldName);
                }

                @Override
                public GlobalIDCodec getGlobalIDCodec() {
                    return InternalContext.from(inner).getGlobalIDCodec();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
                    return InternalContext.from(inner).deserializeGlobalID(serialized);
                }
            }

            /**
             * Resolves the node field value for a single parent object.
             * Override this method to implement single-item resolution.
             *
             * @param ctx the execution context
             * @return a future that completes with the resolved value
             */
            public abstract CompletableFuture<com.example.grts.Node> resolve(Context ctx);

            @Override
            @SuppressWarnings("unchecked")
            public final CompletableFuture<?> invokeFieldResolver(
                FieldExecutionContext<?, ?, ?, ?> context) {
                return resolve(new Context((FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Node_Arguments, com.example.grts.Node>) context));
            }
        }

        @ResolverFor(typeName = "Query", fieldName = "nodes", isSelective = false, isBatching = false)
        public abstract static class Nodes
            implements FieldResolverBase<List<com.example.grts.Node>, com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Nodes_Arguments, com.example.grts.Node>, BaseUnbatchedFieldResolver {

            /**
             * Context for Query.nodes resolver.
             * Provides type-safe access to object value, query value, arguments, and selections.
             */
            public static final class Context
                implements FieldResolverBase.Context<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Nodes_Arguments, com.example.grts.Node>, InternalContext {

                private final FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Nodes_Arguments, com.example.grts.Node> inner;

                public Context(FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Nodes_Arguments, com.example.grts.Node> inner) {
                    this.inner = inner;
                }

                @Override
                public com.example.grts.Query getObjectValue() {
                    return inner.getObjectValue();
                }

                @Override
                public com.example.grts.Query getQueryValue() {
                    return inner.getQueryValue();
                }

                @Override
                public com.example.grts.Query_Nodes_Arguments getArguments() {
                    return inner.getArguments();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> globalIDFor(Type<T> type, String internalID) {
                    return inner.globalIDFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> String serialize(GlobalID<T> globalID) {
                    return inner.serialize(globalID);
                }

                @Override
                public Object getRequestContext() {
                    return inner.getRequestContext();
                }

                @Override
                public <T extends NodeObject> String globalIDStringFor(Type<T> type, String internalID) {
                    return inner.globalIDStringFor(type, internalID);
                }

                @Override
                public <T extends NodeCompositeOutput> T nodeRef(GlobalID<T> id) {
                    return inner.nodeRef(id);
                }

                @Override
                public <A extends Arguments, T extends GraphQLObject> T rootFieldRef(
                        RootObjectField<?, T, A> field, A arguments) {
                    return inner.rootFieldRef(field, arguments);
                }

                @Override
                public <T> CompletableFuture<T> query(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> query(QueryFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(operation, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(MutationFromAnnotation operation, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(operation, variables, targetClass);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections) {
                    return inner.query(selections, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections, Map<String, Object> variables) {
                    return inner.query(selections, variables, com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation) {
                    return inner.query(operation, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(QueryFromAnnotation operation, Map<String, Object> variables) {
                    return inner.query(operation, variables, com.example.grts.Query.class);
                }
                public CompletableFuture<com.example.grts.Mutation> mutation(String selections) {
                    return inner.mutation(selections, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(String selections, Map<String, Object> variables) {
                    return inner.mutation(selections, variables, com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation) {
                    return inner.mutation(operation, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(MutationFromAnnotation operation, Map<String, Object> variables) {
                    return inner.mutation(operation, variables, com.example.grts.Mutation.class);
                }

                @Override
                public ViaductSchema getSchema() {
                    return InternalContext.from(inner).getSchema();
                }

                @Override
                public GraphQLInputObjectType getArgumentsInputType(
                        String name, String containingTypeName, String fieldName) {
                    return InternalContext.from(inner)
                            .getArgumentsInputType(name, containingTypeName, fieldName);
                }

                @Override
                public GlobalIDCodec getGlobalIDCodec() {
                    return InternalContext.from(inner).getGlobalIDCodec();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
                    return InternalContext.from(inner).deserializeGlobalID(serialized);
                }
            }

            /**
             * Resolves the nodes field value for a single parent object.
             * Override this method to implement single-item resolution.
             *
             * @param ctx the execution context
             * @return a future that completes with the resolved value
             */
            public abstract CompletableFuture<List<com.example.grts.Node>> resolve(Context ctx);

            @Override
            @SuppressWarnings("unchecked")
            public final CompletableFuture<?> invokeFieldResolver(
                FieldExecutionContext<?, ?, ?, ?> context) {
                return resolve(new Context((FieldExecutionContext<com.example.grts.Query, com.example.grts.Query, com.example.grts.Query_Nodes_Arguments, com.example.grts.Node>) context));
            }
        }

}