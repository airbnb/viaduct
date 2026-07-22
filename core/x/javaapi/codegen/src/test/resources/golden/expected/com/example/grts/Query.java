package com.example.grts;

import viaduct.engine.api.EngineObjectData;
import viaduct.engine.api.NodeReference;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.NodeObjectBase;
import viaduct.java.api.internal.ObjectBase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Query extends ObjectBase implements viaduct.java.api.types.Query {

    public Query(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private Query(InternalContext context, Map<String, Object> data) {
        super(context, data);
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