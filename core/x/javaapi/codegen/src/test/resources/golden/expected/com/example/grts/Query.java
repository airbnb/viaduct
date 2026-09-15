package com.example.grts;

import viaduct.engine.api.EngineObjectData;
import viaduct.engine.api.NodeReference;
import viaduct.engine.api.RootFieldReference;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.context.RootFieldCall;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.NodeObjectBase;
import viaduct.java.api.internal.ObjectBase;
import viaduct.java.api.internal.OutputBuilderTypeChecker;
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
import java.util.function.Consumer;
import java.util.List;
import java.util.Map;

@SuppressWarnings("MissingOverride")
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

        public static OrderRootFieldCall order() {
            return new OrderRootFieldCall();
        }

        /** Collects the arguments for a call to the order root field. */
        public static final class OrderRootFieldCall {
            private Consumer<Query_Order_Arguments.Builder> __arguments = __builder -> {};

            private OrderRootFieldCall() {}

            public OrderRootFieldCall id(String id) {
                __arguments = __arguments.andThen(__builder -> __builder.id(id));
                return this;
            }

            public RootFieldCall<Order> build() {
                return new RootFieldCall<Order>() {
                    @Override
                    public RootObjectField<?, Order, ? extends Arguments> field() {
                        return Fields.order;
                    }

                    @Override
                    public Arguments arguments(ExecutionContext context) {
                        Query_Order_Arguments.Builder __builder = Query_Order_Arguments.builder(context);
                        __arguments.accept(__builder);
                        return __builder.build();
                    }
                };
            }
        }

        public static RootFieldCall<User> topUser() {
            return new RootFieldCall<User>() {
                @Override
                public RootObjectField<?, User, ? extends Arguments> field() {
                    return Fields.topUser;
                }

                @Override
                public Arguments arguments(ExecutionContext context) {
                    return Arguments.None;
                }
            };
        }

        public static OrdersConnectionRootFieldCall ordersConnection() {
            return new OrdersConnectionRootFieldCall();
        }

        /** Collects the arguments for a call to the ordersConnection root field. */
        public static final class OrdersConnectionRootFieldCall {
            private Consumer<Query_OrdersConnection_Arguments.Builder> __arguments = __builder -> {};

            private OrdersConnectionRootFieldCall() {}

            public OrdersConnectionRootFieldCall first(Integer first) {
                __arguments = __arguments.andThen(__builder -> __builder.first(first));
                return this;
            }

            public RootFieldCall<OrderConnection> build() {
                return new RootFieldCall<OrderConnection>() {
                    @Override
                    public RootObjectField<?, OrderConnection, ? extends Arguments> field() {
                        return Fields.ordersConnection;
                    }

                    @Override
                    public Arguments arguments(ExecutionContext context) {
                        Query_OrdersConnection_Arguments.Builder __builder = Query_OrdersConnection_Arguments.builder(context);
                        __arguments.accept(__builder);
                        return __builder.build();
                    }
                };
            }
        }

        public static LookupOrderRootFieldCall lookupOrder() {
            return new LookupOrderRootFieldCall();
        }

        /** Collects the arguments for a call to the lookupOrder root field. */
        public static final class LookupOrderRootFieldCall {
            private Consumer<Query_LookupOrder_Arguments.Builder> __arguments = __builder -> {};

            private LookupOrderRootFieldCall() {}

            public LookupOrderRootFieldCall filter(OrderLookupInput filter) {
                __arguments = __arguments.andThen(__builder -> __builder.filter(filter));
                return this;
            }

            public RootFieldCall<Order> build() {
                return new RootFieldCall<Order>() {
                    @Override
                    public RootObjectField<?, Order, ? extends Arguments> field() {
                        return Fields.lookupOrder;
                    }

                    @Override
                    public Arguments arguments(ExecutionContext context) {
                        Query_LookupOrder_Arguments.Builder __builder = Query_LookupOrder_Arguments.builder(context);
                        __arguments.accept(__builder);
                        return __builder.build();
                    }
                };
            }
        }


    public Query(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    @SuppressWarnings("UnusedMethod")
    private Query(InternalContext context, Map<String, Object> data) {
        super(context, data, "Query");
    }

    private Query(InternalContext context, ObjectBase base, Map<String, Object> data) {
        super(context, base, data, "Query");
    }

    public Query(InternalContext context, RootFieldReference rootFieldReference) {
        super(context, rootFieldReference);
    }
        public Order getOrderOrThrow(String alias) {
            return fetchObject("order", alias, Order.class, Order::new);
        }

        public Order getOrderOrThrow() {
            return fetchObject("order", null, Order.class, Order::new);
        }

        public Order getOrder(String alias) {
            return nullOnDataFailure(() -> fetchObject("order", alias, Order.class, Order::new));
        }

        public Order getOrder() {
            return nullOnDataFailure(() -> fetchObject("order", null, Order.class, Order::new));
        }

        public Order getOrderOrNull(String alias) {
            return nullOnDataFailure(() -> fetchObject("order", alias, Order.class, Order::new));
        }

        public Order getOrderOrNull() {
            return nullOnDataFailure(() -> fetchObject("order", null, Order.class, Order::new));
        }

        public User getTopUserOrThrow(String alias) {
            return fetchObject("topUser", alias, User.class, User::new);
        }

        public User getTopUserOrThrow() {
            return fetchObject("topUser", null, User.class, User::new);
        }

        public User getTopUser(String alias) {
            return nullOnDataFailure(() -> fetchObject("topUser", alias, User.class, User::new));
        }

        public User getTopUser() {
            return nullOnDataFailure(() -> fetchObject("topUser", null, User.class, User::new));
        }

        public User getTopUserOrNull(String alias) {
            return nullOnDataFailure(() -> fetchObject("topUser", alias, User.class, User::new));
        }

        public User getTopUserOrNull() {
            return nullOnDataFailure(() -> fetchObject("topUser", null, User.class, User::new));
        }

        public List<Order> getPopularOrdersOrThrow(String alias) {
            return fetchObjectList("popularOrders", alias, Order.class, Order::new);
        }

        public List<Order> getPopularOrdersOrThrow() {
            return fetchObjectList("popularOrders", null, Order.class, Order::new);
        }

        public List<Order> getPopularOrders(String alias) {
            return nullOnDataFailure(() -> fetchObjectList("popularOrders", alias, Order.class, Order::new));
        }

        public List<Order> getPopularOrders() {
            return nullOnDataFailure(() -> fetchObjectList("popularOrders", null, Order.class, Order::new));
        }

        public List<Order> getPopularOrdersOrNull(String alias) {
            return nullOnDataFailure(() -> fetchObjectList("popularOrders", alias, Order.class, Order::new));
        }

        public List<Order> getPopularOrdersOrNull() {
            return nullOnDataFailure(() -> fetchObjectList("popularOrders", null, Order.class, Order::new));
        }

        public List<User> getTrendingUsersOrThrow(String alias) {
            return fetchObjectList("trendingUsers", alias, User.class, User::new);
        }

        public List<User> getTrendingUsersOrThrow() {
            return fetchObjectList("trendingUsers", null, User.class, User::new);
        }

        public List<User> getTrendingUsers(String alias) {
            return nullOnDataFailure(() -> fetchObjectList("trendingUsers", alias, User.class, User::new));
        }

        public List<User> getTrendingUsers() {
            return nullOnDataFailure(() -> fetchObjectList("trendingUsers", null, User.class, User::new));
        }

        public List<User> getTrendingUsersOrNull(String alias) {
            return nullOnDataFailure(() -> fetchObjectList("trendingUsers", alias, User.class, User::new));
        }

        public List<User> getTrendingUsersOrNull() {
            return nullOnDataFailure(() -> fetchObjectList("trendingUsers", null, User.class, User::new));
        }

        public OrderConnection getOrdersConnectionOrThrow(String alias) {
            return fetchObject("ordersConnection", alias, OrderConnection.class, OrderConnection::new);
        }

        public OrderConnection getOrdersConnectionOrThrow() {
            return fetchObject("ordersConnection", null, OrderConnection.class, OrderConnection::new);
        }

        public OrderConnection getOrdersConnection(String alias) {
            return nullOnDataFailure(() -> fetchObject("ordersConnection", alias, OrderConnection.class, OrderConnection::new));
        }

        public OrderConnection getOrdersConnection() {
            return nullOnDataFailure(() -> fetchObject("ordersConnection", null, OrderConnection.class, OrderConnection::new));
        }

        public OrderConnection getOrdersConnectionOrNull(String alias) {
            return nullOnDataFailure(() -> fetchObject("ordersConnection", alias, OrderConnection.class, OrderConnection::new));
        }

        public OrderConnection getOrdersConnectionOrNull() {
            return nullOnDataFailure(() -> fetchObject("ordersConnection", null, OrderConnection.class, OrderConnection::new));
        }

        public Order getLookupOrderOrThrow(String alias) {
            return fetchObject("lookupOrder", alias, Order.class, Order::new);
        }

        public Order getLookupOrderOrThrow() {
            return fetchObject("lookupOrder", null, Order.class, Order::new);
        }

        public Order getLookupOrder(String alias) {
            return nullOnDataFailure(() -> fetchObject("lookupOrder", alias, Order.class, Order::new));
        }

        public Order getLookupOrder() {
            return nullOnDataFailure(() -> fetchObject("lookupOrder", null, Order.class, Order::new));
        }

        public Order getLookupOrderOrNull(String alias) {
            return nullOnDataFailure(() -> fetchObject("lookupOrder", alias, Order.class, Order::new));
        }

        public Order getLookupOrderOrNull() {
            return nullOnDataFailure(() -> fetchObject("lookupOrder", null, Order.class, Order::new));
        }

        public Node getNodeOrThrow(String alias) {
            return fetchAbstractObject("node", alias, Node.class);
        }

        public Node getNodeOrThrow() {
            return fetchAbstractObject("node", null, Node.class);
        }

        public Node getNode(String alias) {
            return nullOnDataFailure(() -> fetchAbstractObject("node", alias, Node.class));
        }

        public Node getNode() {
            return nullOnDataFailure(() -> fetchAbstractObject("node", null, Node.class));
        }

        public Node getNodeOrNull(String alias) {
            return nullOnDataFailure(() -> fetchAbstractObject("node", alias, Node.class));
        }

        public Node getNodeOrNull() {
            return nullOnDataFailure(() -> fetchAbstractObject("node", null, Node.class));
        }

        public List<Node> getNodesOrThrow(String alias) {
            return fetchAbstractObjectList("nodes", alias, Node.class);
        }

        public List<Node> getNodesOrThrow() {
            return fetchAbstractObjectList("nodes", null, Node.class);
        }

        public List<Node> getNodes(String alias) {
            return nullOnDataFailure(() -> fetchAbstractObjectList("nodes", alias, Node.class));
        }

        public List<Node> getNodes() {
            return nullOnDataFailure(() -> fetchAbstractObjectList("nodes", null, Node.class));
        }

        public List<Node> getNodesOrNull(String alias) {
            return nullOnDataFailure(() -> fetchAbstractObjectList("nodes", alias, Node.class));
        }

        public List<Node> getNodesOrNull() {
            return nullOnDataFailure(() -> fetchAbstractObjectList("nodes", null, Node.class));
        }


    public Builder toBuilder() {
        return new Builder(__context(), toBuilderBase());
    }

    public static Builder builder(ExecutionContext context) {
        return new Builder(InternalContext.from(context), null);
    }

    public static class Builder {
        private final InternalContext __context;
        private final ObjectBase __base;
        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder(InternalContext __context, ObjectBase __base) {
            this.__context = __context;
            this.__base = __base;
        }

                public Builder order(Order order) {
                    order = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Query",
                            "order",
                            Order.class,
                            order);
                    data.put("order", order);
        return this;
                }

                public Builder topUser(User topUser) {
                    topUser = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Query",
                            "topUser",
                            User.class,
                            topUser);
                    data.put("topUser", topUser);
        return this;
                }

                public Builder popularOrders(List<Order> popularOrders) {
                    popularOrders = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Query",
                            "popularOrders",
                            Order.class,
                            popularOrders);
                    data.put("popularOrders", popularOrders);
        return this;
                }

                public Builder trendingUsers(List<User> trendingUsers) {
                    trendingUsers = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Query",
                            "trendingUsers",
                            User.class,
                            trendingUsers);
                    data.put("trendingUsers", trendingUsers);
        return this;
                }

                public Builder ordersConnection(OrderConnection ordersConnection) {
                    ordersConnection = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Query",
                            "ordersConnection",
                            OrderConnection.class,
                            ordersConnection);
                    data.put("ordersConnection", ordersConnection);
        return this;
                }

                public Builder lookupOrder(Order lookupOrder) {
                    lookupOrder = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Query",
                            "lookupOrder",
                            Order.class,
                            lookupOrder);
                    data.put("lookupOrder", lookupOrder);
        return this;
                }

                public Builder node(Node node) {
                    node = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Query",
                            "node",
                            Node.class,
                            node);
                    data.put("node", node);
        return this;
                }

                public Builder nodes(List<Node> nodes) {
                    nodes = OutputBuilderTypeChecker.checkField(
                            __context,
                            "Query",
                            "nodes",
                            Node.class,
                            nodes);
                    data.put("nodes", nodes);
        return this;
                }


        public Query build() {
            return new Query(__context, __base, new LinkedHashMap<>(data));
        }
    }
}