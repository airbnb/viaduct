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

    private Query(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }

    public Query(InternalContext context, RootFieldReference rootFieldReference) {
        super(context, rootFieldReference);
    }
        public Order getOrderOrThrow() {
            return fetchObject("order", Order::new);
        }
        public Order getOrder() {
            return fetchObject("order", Order::new);
        }

        public User getTopUserOrThrow() {
            return fetchObject("topUser", User::new);
        }
        public User getTopUser() {
            return fetchObject("topUser", User::new);
        }

        public List<Order> getPopularOrdersOrThrow() {
            return fetchObjectList("popularOrders", Order::new);
        }
        public List<Order> getPopularOrders() {
            return fetchObjectList("popularOrders", Order::new);
        }

        public List<User> getTrendingUsersOrThrow() {
            return fetchObjectList("trendingUsers", User::new);
        }
        public List<User> getTrendingUsers() {
            return fetchObjectList("trendingUsers", User::new);
        }

        public OrderConnection getOrdersConnectionOrThrow() {
            return fetchObject("ordersConnection", OrderConnection::new);
        }
        public OrderConnection getOrdersConnection() {
            return fetchObject("ordersConnection", OrderConnection::new);
        }

        public Order getLookupOrderOrThrow() {
            return fetchObject("lookupOrder", Order::new);
        }
        public Order getLookupOrder() {
            return fetchObject("lookupOrder", Order::new);
        }

        public Node getNodeOrThrow() {
            return fetchAbstractObject("node", Node.class);
        }
        public Node getNode() {
            return fetchAbstractObject("node", Node.class);
        }

        public List<Node> getNodesOrThrow() {
            return fetchAbstractObjectList("nodes", Node.class);
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
            return new Query(__context, new LinkedHashMap<>(data));
        }
    }
}