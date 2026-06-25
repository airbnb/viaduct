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

public class OrderConnection extends ObjectBase {

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


    public static Builder builder(ExecutionContext context) {
        return new Builder(InternalContext.from(context));
    }

    public static class Builder {
        private final InternalContext __context;
        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder(InternalContext __context) {
            this.__context = __context;
        }

                public Builder edges(List<OrderEdge> edges) {
                    data.put("edges", edges);
        return this;
                }

                public Builder pageInfo(PageInfo pageInfo) {
                    data.put("pageInfo", pageInfo);
        return this;
                }


        public OrderConnection build() {
            return new OrderConnection(__context, new LinkedHashMap<>(data));
        }
    }
}