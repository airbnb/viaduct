package com.example.grts;

import viaduct.engine.api.EngineObjectData;
import viaduct.engine.api.NodeReference;
import viaduct.engine.api.RootFieldReference;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.NodeObjectBase;
import viaduct.java.api.internal.ObjectBase;
import viaduct.java.api.reflect.CompositeField;
import viaduct.java.api.reflect.Field;
import viaduct.java.api.reflect.RootObjectField;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.reflect.TypeFields;
import viaduct.java.api.types.Arguments;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Query extends ObjectBase implements viaduct.java.api.types.Query {

    public static final Type<Query> Reflection = Type.ofClass(Query.class);

    public static final class Fields implements TypeFields<Query> {
        private Fields() {}

        public static final Field<Query> __typename =
                Field.of("__typename", Reflection);
                public static final RootObjectField<Query, Order, Query_Order_Arguments> order =
                                RootObjectField.of("order", Reflection, Order.Reflection, List.of("order"));

                public static final RootObjectField<Query, User, Arguments.NoArguments> topUser =
                                RootObjectField.of("topUser", Reflection, User.Reflection, List.of("topUser"));

                public static final CompositeField<Query, Order> popularOrders =
                                CompositeField.of("popularOrders", Reflection, Order.Reflection);

                public static final CompositeField<Query, User> trendingUsers =
                                CompositeField.of("trendingUsers", Reflection, User.Reflection);

                public static final RootObjectField<Query, OrderConnection, Query_OrdersConnection_Arguments> ordersConnection =
                                RootObjectField.of("ordersConnection", Reflection, OrderConnection.Reflection, List.of("ordersConnection"));

                public static final RootObjectField<Query, Order, Query_LookupOrder_Arguments> lookupOrder =
                                RootObjectField.of("lookupOrder", Reflection, Order.Reflection, List.of("lookupOrder"));

                public static final CompositeField<Query, Node> node =
                                CompositeField.of("node", Reflection, Node.Reflection);

                public static final CompositeField<Query, Node> nodes =
                                CompositeField.of("nodes", Reflection, Node.Reflection);

    }

    public Query(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private Query(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }

    public Query(InternalContext context, RootFieldReference rootFieldReference) {
        super(context, rootFieldReference);
    }
        public Order getOrder() {
            return fetchObject("order", Order::new);
        }

        public User getTopUser() {
            return fetchObject("topUser", User::new);
        }

        public List<Order> getPopularOrders() {
            return fetchObjectList("popularOrders", Order::new);
        }

        public List<User> getTrendingUsers() {
            return fetchObjectList("trendingUsers", User::new);
        }

        public OrderConnection getOrdersConnection() {
            return fetchObject("ordersConnection", OrderConnection::new);
        }

        public Order getLookupOrder() {
            return fetchObject("lookupOrder", Order::new);
        }

        public Node getNode() {
            return fetchAbstractObject("node", Node.class);
        }

        public List<Node> getNodes() {
            return fetchAbstractObjectList("nodes", Node.class);
        }


    public static Builder builder(ExecutionContext context) {
        return new Builder(InternalContext.from(context));
    }

    public static class Builder {
        private final InternalContext __context;
        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder(InternalContext __context) {
            this.__context = __context;
        }

                public Builder order(Order order) {
                    data.put("order", order);
        return this;
                }

                public Builder topUser(User topUser) {
                    data.put("topUser", topUser);
        return this;
                }

                public Builder popularOrders(List<Order> popularOrders) {
                    data.put("popularOrders", popularOrders);
        return this;
                }

                public Builder trendingUsers(List<User> trendingUsers) {
                    data.put("trendingUsers", trendingUsers);
        return this;
                }

                public Builder ordersConnection(OrderConnection ordersConnection) {
                    data.put("ordersConnection", ordersConnection);
        return this;
                }

                public Builder lookupOrder(Order lookupOrder) {
                    data.put("lookupOrder", lookupOrder);
        return this;
                }

                public Builder node(Node node) {
                    data.put("node", node);
        return this;
                }

                public Builder nodes(List<Node> nodes) {
                    data.put("nodes", nodes);
        return this;
                }


        public Query build() {
            return new Query(__context, new LinkedHashMap<>(data));
        }
    }
}