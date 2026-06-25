package com.example.tenant.resolverbases;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import viaduct.engine.api.ViaductSchema;
import viaduct.java.api.annotations.NodeResolverFor;
import viaduct.java.api.context.NodeExecutionContext;
import viaduct.java.api.context.SelectiveNodeExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.ResolverClassFinder;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.resolvers.FieldValue;
import viaduct.java.api.resolvers.NodeResolverBase;
import viaduct.java.api.types.NodeCompositeOutput;
import viaduct.java.api.types.NodeObject;
import viaduct.service.api.spi.GlobalIDCodec;
import com.example.grts.*;

/**
 * Generated node resolver base classes.
 */
public final class NodeResolvers {

    private NodeResolvers() {}

        @NodeResolverFor(typeName = "User", isBatching = false, isSelective = false)
        public abstract static class User implements NodeResolverBase<com.example.grts.User> {

            /**
             * Context for User node resolver.
             */
            public static final class Context implements NodeExecutionContext<com.example.grts.User>, NodeResolverBase.Context<com.example.grts.User>, InternalContext {

                private final NodeExecutionContext<com.example.grts.User> inner;

                @SuppressWarnings("unchecked")
                public Context(NodeExecutionContext<?> inner) {
                    this.inner = (NodeExecutionContext<com.example.grts.User>) inner;
                }

                @Override
                public GlobalID<com.example.grts.User> getId() {
                    return inner.getId();
                }

                @Override
                public Object getRequestContext() {
                    return inner.getRequestContext();
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

            public abstract CompletableFuture<com.example.grts.User> resolve(Context ctx);
        }

}