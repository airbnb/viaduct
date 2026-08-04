package com.example.grts;

import graphql.schema.GraphQLInputObjectType;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.CompositeField;
import viaduct.java.api.reflect.Field;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.reflect.TypeFields;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.List;
import java.util.Map;
import viaduct.apiannotations.InternalApi;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.internal.InputBase;
import viaduct.java.api.internal.InternalContext;

/** Generated arguments class for resolver field. */
public class Query_Node_Arguments extends InputBase implements Arguments {

    public static final Type<Query_Node_Arguments> Reflection = Type.ofClass(Query_Node_Arguments.class);

    public static final class Fields implements TypeFields<Query_Node_Arguments> {
        private Fields() {}

        public static final Field<Query_Node_Arguments> __typename =
                Field.of("__typename", Reflection);
                public static final Field<Query_Node_Arguments> id =
                                Field.of("id", Reflection);

    }

    // Public because the framework constructs arguments reflectively across packages
    // (JavaFieldResolverExecutorImpl, VariablesProviderExecutorImpl, etc.). @InternalApi
    // marks it as not-for-tenant-use, mirroring Kotlin's `internal constructor`.
    @InternalApi
    public Query_Node_Arguments(InternalContext context, Map<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
        super(context, data, graphQLInputObjectType);
    }

        public String getId() {
            return get("id");
        }

}