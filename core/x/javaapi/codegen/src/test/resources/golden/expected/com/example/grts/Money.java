package com.example.grts;

import viaduct.engine.api.EngineObjectData;
import viaduct.engine.api.NodeReference;
import viaduct.engine.api.RootFieldReference;
import viaduct.java.api.context.ExecutionContext;
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
import java.util.List;
import java.util.Map;

public class Money extends ObjectBase implements SearchHit {

    public static final Type<Money> Reflection = Type.ofClass(Money.class);

    public static final class Fields implements TypeFields<Money> {
        private Fields() {}

        public static final Field<Money> __typename =
                Field.of("__typename", Reflection);
                public static final Field<Money> amount =
                                Field.of("amount", Reflection);

                public static final Field<Money> currency =
                                Field.of("currency", Reflection);

    }

    public Money(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private Money(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }

    public Money(InternalContext context, RootFieldReference rootFieldReference) {
        super(context, rootFieldReference);
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
                    amount = OutputBuilderTypeChecker.checkField(__context, "Money", "amount", amount);
                    data.put("amount", amount);
        return this;
                }

                public Builder currency(String currency) {
                    currency = OutputBuilderTypeChecker.checkField(__context, "Money", "currency", currency);
                    data.put("currency", currency);
        return this;
                }


        public Money build() {
            return new Money(__context, new LinkedHashMap<>(data));
        }
    }
}