package com.example.grts;

import graphql.schema.GraphQLInputObjectType;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.CompositeField;
import viaduct.java.api.reflect.Field;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.reflect.TypeFields;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import viaduct.apiannotations.InternalApi;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.internal.InputBase;
import viaduct.java.api.internal.InternalContext;

/** Generated arguments class for resolver field. */
public class Query_LookupOrder_Arguments extends InputBase implements Arguments {

    public static final Type<Query_LookupOrder_Arguments> Reflection = Type.ofClass(Query_LookupOrder_Arguments.class);

    public static final class Fields implements TypeFields<Query_LookupOrder_Arguments> {
        private Fields() {}

        public static final Field<Query_LookupOrder_Arguments> __typename =
                Field.of("__typename", Reflection);
                public static final CompositeField<Query_LookupOrder_Arguments, OrderLookupInput> filter =
                                CompositeField.of("filter", Reflection, OrderLookupInput.Reflection);

    }

    // Public because the framework constructs arguments reflectively across packages
    // (JavaFieldResolverExecutorImpl, VariablesProviderExecutorImpl, etc.). @InternalApi
    // marks it as not-for-tenant-use, mirroring Kotlin's `internal constructor`.
    @InternalApi
    public Query_LookupOrder_Arguments(InternalContext context, Map<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
        super(context, data, graphQLInputObjectType);
    }

    /**
     * Returns whether this input contains a value for {@code field} after GraphQL
     * defaults are applied. Explicit {@code null} counts as present. An omitted field
     * with a schema default is present; an omitted field without a default is absent.
     */
    public boolean isPresent(Field<Query_LookupOrder_Arguments> field) {
        return isFieldPresent(field);
    }

        public OrderLookupInput getFilter() {
            return getInput("filter", OrderLookupInput::new);
        }


    public static Builder builder(ExecutionContext context) {
        return new Builder(InternalContext.from(context));
    }

    public static class Builder {
        private final InternalContext __context;
        private final GraphQLInputObjectType graphQLInputObjectType;
        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder(InternalContext __context) {
            this.__context = __context;
            this.graphQLInputObjectType =
                    __context.getArgumentsInputType(
                            "Query_LookupOrder_Arguments",
                            "Query",
                            "lookupOrder");
        }

                public Builder filter(OrderLookupInput filter) {
                    data.put("filter", filter);
        return this;
                }


        public Query_LookupOrder_Arguments build() {
            return new Query_LookupOrder_Arguments(
                    __context, new LinkedHashMap<>(data), graphQLInputObjectType);
        }
    }
}