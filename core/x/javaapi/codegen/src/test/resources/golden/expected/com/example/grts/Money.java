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

public class Money extends ObjectBase implements SearchHit {

    public Money(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private Money(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }
        public double getAmount() {
            return fetchScalar("amount");
        }

        public String getCurrency() {
            return fetchScalar("currency");
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

                public Builder amount(double amount) {
                    data.put("amount", amount);
        return this;
                }

                public Builder currency(String currency) {
                    data.put("currency", currency);
        return this;
                }


        public Money build() {
            return new Money(__context, new LinkedHashMap<>(data));
        }
    }
}