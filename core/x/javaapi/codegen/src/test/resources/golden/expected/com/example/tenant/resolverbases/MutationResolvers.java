package com.example.tenant.resolverbases;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.engine.api.ViaductSchema;
import viaduct.java.api.annotations.ResolverFor;
import viaduct.java.api.context.FieldExecutionContext;
import viaduct.java.api.context.SelectiveFieldExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.BaseUnbatchedFieldResolver;
import viaduct.java.api.internal.ResolverClassFinder;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.resolvers.FieldResolverBase;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.java.api.types.NodeObject;
import viaduct.service.api.spi.GlobalIDCodec;
import com.example.grts.*;

/**
 * Generated resolver base classes for Mutation type.
 */
public final class MutationResolvers {

    private MutationResolvers() {
        // Utility class
    }

        @ResolverFor(typeName = "Mutation", fieldName = "createOrder", isSelective = false, isBatching = false)
        public abstract static class CreateOrder
            implements FieldResolverBase<com.example.grts.Order, com.example.grts.Mutation, com.example.grts.Query, com.example.grts.Mutation_CreateOrder_Arguments, com.example.grts.Order>, BaseUnbatchedFieldResolver {

            /**
             * Context for Mutation.createOrder resolver.
             * Provides type-safe access to object value, query value, arguments, and selections.
             */
            public static final class Context
                implements FieldResolverBase.Context<com.example.grts.Mutation, com.example.grts.Query, com.example.grts.Mutation_CreateOrder_Arguments, com.example.grts.Order>, InternalContext {

                private final FieldExecutionContext<com.example.grts.Mutation, com.example.grts.Query, com.example.grts.Mutation_CreateOrder_Arguments, com.example.grts.Order> inner;

                public Context(FieldExecutionContext<com.example.grts.Mutation, com.example.grts.Query, com.example.grts.Mutation_CreateOrder_Arguments, com.example.grts.Order> inner) {
                    this.inner = inner;
                }

                @Override
                public com.example.grts.Mutation getObjectValue() {
                    return inner.getObjectValue();
                }

                @Override
                public com.example.grts.Query getQueryValue() {
                    return inner.getQueryValue();
                }

                @Override
                public com.example.grts.Mutation_CreateOrder_Arguments getArguments() {
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
                public <T> CompletableFuture<T> query(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.query(selections, variables, targetClass);
                }

                @Override
                public <T> CompletableFuture<T> mutation(String selections, Map<String, Object> variables, Class<T> targetClass) {
                    return inner.mutation(selections, variables, targetClass);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections) {
                    return inner.query(selections, java.util.Map.of(), com.example.grts.Query.class);
                }

                public CompletableFuture<com.example.grts.Query> query(String selections, Map<String, Object> variables) {
                    return inner.query(selections, variables, com.example.grts.Query.class);
                }
                public CompletableFuture<com.example.grts.Mutation> mutation(String selections) {
                    return inner.mutation(selections, java.util.Map.of(), com.example.grts.Mutation.class);
                }

                public CompletableFuture<com.example.grts.Mutation> mutation(String selections, Map<String, Object> variables) {
                    return inner.mutation(selections, variables, com.example.grts.Mutation.class);
                }

                @Override
                public ViaductSchema getSchema() {
                    return InternalContext.from(inner).getSchema();
                }

                @Override
                public GlobalIDCodec getGlobalIDCodec() {
                    return InternalContext.from(inner).getGlobalIDCodec();
                }

                @Override
                public ResolverClassFinder getClassFinder() {
                    return InternalContext.from(inner).getClassFinder();
                }

                @Override
                public <T extends NodeCompositeOutput> GlobalID<T> deserializeGlobalID(String serialized) {
                    return InternalContext.from(inner).deserializeGlobalID(serialized);
                }
            }

            /**
             * Resolves the createOrder field value for a single parent object.
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
                return resolve(new Context((FieldExecutionContext<com.example.grts.Mutation, com.example.grts.Query, com.example.grts.Mutation_CreateOrder_Arguments, com.example.grts.Order>) context));
            }
        }

}