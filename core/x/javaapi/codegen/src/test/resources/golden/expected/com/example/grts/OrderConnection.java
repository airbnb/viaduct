package com.example.grts;

import viaduct.engine.api.EngineObjectData;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.ConnectionBuilder;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.ObjectBase;
import viaduct.java.api.reflect.CompositeField;
import viaduct.java.api.reflect.Field;
import viaduct.java.api.reflect.RootObjectField;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.reflect.TypeFields;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.types.ConnectionArguments;
import viaduct.java.api.types.OffsetLimit;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class OrderConnection extends ObjectBase implements viaduct.java.api.types.Connection<OrderEdge, Order> {

    public static final Type<OrderConnection> Reflection = Type.ofClass(OrderConnection.class);

    public static final class Fields implements TypeFields<OrderConnection> {
        private Fields() {}

        public static final Field<OrderConnection> __typename =
                Field.of("__typename", Reflection);
                public static final CompositeField<OrderConnection, OrderEdge> edges =
                                CompositeField.of("edges", Reflection, OrderEdge.Reflection);

                public static final CompositeField<OrderConnection, PageInfo> pageInfo =
                                CompositeField.of("pageInfo", Reflection, PageInfo.Reflection);

                public static final Field<OrderConnection> totalCount =
                                Field.of("totalCount", Reflection);

    }

    public OrderConnection(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private OrderConnection(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }

        public List<OrderEdge> getEdges() {
            return fetchObjectList("edges", OrderEdge::new);
        }

        public PageInfo getPageInfo() {
            return fetchObject("pageInfo", PageInfo::new);
        }

        public Integer getTotalCount() {
            return fetchScalar("totalCount");
        }


    public static Builder builder(ExecutionContext context) {
        return new Builder(context);
    }

    /**
     * Pagination-aware builder. Extends {@link ConnectionBuilder} with {@code fromEdges},
     * {@code fromSlice}, and {@code fromList}; the base builds the OrderEdge,
     * PageInfo, and OrderConnection GRTs from the field's context and these Class handles.
     * The generated per-field setters populate additional connection fields (e.g.
     * {@code totalCount}) alongside the pagination-produced {@code edges}/{@code pageInfo};
     * a pagination method and any setters can be combined in any order before {@code build()}.
     */
    public static class Builder extends ConnectionBuilder<OrderConnection, OrderEdge, Order> {
        private final InternalContext __context;

        private Builder(ExecutionContext context) {
            super(context, OrderConnection.class, OrderEdge.class);
            this.__context = InternalContext.from(context);
        }

        @Override
        public Builder fromEdges(List<OrderEdge> edges) {
            super.fromEdges(edges);
            return this;
        }

        @Override
        public Builder fromEdges(
                List<OrderEdge> edges,
                boolean hasNextPage,
                boolean hasPreviousPage) {
            super.fromEdges(edges, hasNextPage, hasPreviousPage);
            return this;
        }

        @Override
        public <I> Builder fromSlice(
                List<I> items,
                boolean hasNextPage,
                Function<I, Order> buildNode) {
            super.fromSlice(items, hasNextPage, buildNode);
            return this;
        }

        @Override
        public <I> Builder fromSlice(
                List<I> items,
                OffsetLimit offsetLimit,
                boolean hasNextPage,
                Function<I, Order> buildNode) {
            super.fromSlice(items, offsetLimit, hasNextPage, buildNode);
            return this;
        }

        @Override
        public <I> Builder fromList(
                List<I> items,
                Function<I, Order> buildNode) {
            super.fromList(items, buildNode);
            return this;
        }

                public Builder edges(List<OrderEdge> edges) {
                    putField("edges", edges);
                    return this;
                }

                public Builder pageInfo(PageInfo pageInfo) {
                    putField("pageInfo", pageInfo);
                    return this;
                }

                public Builder totalCount(Integer totalCount) {
                    putField("totalCount", totalCount);
                    return this;
                }

    }
}