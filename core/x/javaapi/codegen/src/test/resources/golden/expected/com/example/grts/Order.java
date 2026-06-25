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

public class Order extends NodeObjectBase implements Node, Auditable, Timestamped, SearchHit {

    public Order(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private Order(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }

    public Order(InternalContext context, NodeReference nodeReference) {
        super(context, nodeReference);
    }

        public GlobalID<Order> getId() {
            return fetchGlobalID("id");
        }

        public OrderStatus getStatus() {
            return fetchEnum("status", OrderStatus.class);
        }

        public Money getTotal() {
            return fetchObject("total", Money::new);
        }

        public String getCreatedAt() {
            return fetchScalar("createdAt");
        }

        public String getUpdatedAt() {
            return fetchScalar("updatedAt");
        }

        public List<String> getAuditTrail() {
            return fetchScalarList("auditTrail");
        }

        public User getBuyer() {
            return fetchObject("buyer", User::new);
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

                public Builder id(GlobalID<Order> id) {
                    data.put("id", id == null ? null : __context.getGlobalIDCodec().serialize(id.getType().getName(), id.getInternalID()));
        return this;
                }

                public Builder status(OrderStatus status) {
                    data.put("status", status);
        return this;
                }

                public Builder total(Money total) {
                    data.put("total", total);
        return this;
                }

                public Builder createdAt(String createdAt) {
                    data.put("createdAt", createdAt);
        return this;
                }

                public Builder updatedAt(String updatedAt) {
                    data.put("updatedAt", updatedAt);
        return this;
                }

                public Builder auditTrail(List<String> auditTrail) {
                    data.put("auditTrail", auditTrail);
        return this;
                }

                public Builder buyer(User buyer) {
                    data.put("buyer", buyer);
        return this;
                }


        public Order build() {
            return new Order(__context, new LinkedHashMap<>(data));
        }
    }
}