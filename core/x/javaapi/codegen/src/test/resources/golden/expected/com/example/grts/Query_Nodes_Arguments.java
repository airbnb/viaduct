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
public class Query_Nodes_Arguments extends InputBase implements Arguments {

    public static final Type<Query_Nodes_Arguments> Reflection = Type.ofClass(Query_Nodes_Arguments.class);

    public static final class Fields implements TypeFields<Query_Nodes_Arguments> {
        private Fields() {}

        public static final Field<Query_Nodes_Arguments> __typename =
                Field.of("__typename", Reflection);
                public static final Field<Query_Nodes_Arguments> ids =
                                Field.of("ids", Reflection);

    }

    // Public because the framework constructs arguments reflectively across packages
    // (JavaFieldResolverExecutorImpl, VariablesProviderExecutorImpl, etc.). @InternalApi
    // marks it as not-for-tenant-use, mirroring Kotlin's `internal constructor`.
    @InternalApi
    public Query_Nodes_Arguments(InternalContext context, Map<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
        super(context, data, graphQLInputObjectType);
    }

    /**
     * Returns whether this input contains a value for {@code field} after GraphQL
     * defaults are applied. Explicit {@code null} counts as present. An omitted field
     * with a schema default is present; an omitted field without a default is absent.
     */
    public boolean isPresent(Field<Query_Nodes_Arguments> field) {
        return isFieldPresent(field);
    }

        public List<String> getIds() {
            return getScalarList("ids");
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
                            "Query_Nodes_Arguments",
                            "Query",
                            "nodes");
        }

                public Builder ids(List<String> ids) {
                    data.put("ids", ids);
        return this;
                }


        public Query_Nodes_Arguments build() {
            return new Query_Nodes_Arguments(
                    __context, new LinkedHashMap<>(data), graphQLInputObjectType);
        }
    }
}