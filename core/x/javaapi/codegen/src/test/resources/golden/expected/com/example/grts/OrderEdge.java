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

public class OrderEdge extends ObjectBase {

    public OrderEdge(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private OrderEdge(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }
        public String getCursor() {
            return fetchScalar("cursor");
        }

        public Order getNode() {
            return fetchObject("node", Order::new);
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

                public Builder cursor(String cursor) {
                    data.put("cursor", cursor);
        return this;
                }

                public Builder node(Order node) {
                    data.put("node", node);
        return this;
                }


        public OrderEdge build() {
            return new OrderEdge(__context, new LinkedHashMap<>(data));
        }
    }
}