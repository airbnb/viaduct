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

public class PageInfo extends ObjectBase {

    public PageInfo(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private PageInfo(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }
        public boolean getHasNextPage() {
            return fetchScalar("hasNextPage");
        }

        public boolean getHasPreviousPage() {
            return fetchScalar("hasPreviousPage");
        }

        public String getStartCursor() {
            return fetchScalar("startCursor");
        }

        public String getEndCursor() {
            return fetchScalar("endCursor");
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

                public Builder hasNextPage(boolean hasNextPage) {
                    data.put("hasNextPage", hasNextPage);
        return this;
                }

                public Builder hasPreviousPage(boolean hasPreviousPage) {
                    data.put("hasPreviousPage", hasPreviousPage);
        return this;
                }

                public Builder startCursor(String startCursor) {
                    data.put("startCursor", startCursor);
        return this;
                }

                public Builder endCursor(String endCursor) {
                    data.put("endCursor", endCursor);
        return this;
                }


        public PageInfo build() {
            return new PageInfo(__context, new LinkedHashMap<>(data));
        }
    }
}