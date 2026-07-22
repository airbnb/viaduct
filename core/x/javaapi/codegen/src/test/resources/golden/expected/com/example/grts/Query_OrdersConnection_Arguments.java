package com.example.grts;

import graphql.schema.GraphQLInputObjectType;
import viaduct.java.api.globalid.GlobalID;
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
public class Query_OrdersConnection_Arguments extends InputBase implements Arguments {

    // Public because the framework constructs arguments reflectively across packages
    // (JavaFieldResolverExecutorImpl, VariablesProviderExecutorImpl, etc.). @InternalApi
    // marks it as not-for-tenant-use, mirroring Kotlin's `internal constructor`.
    @InternalApi
    public Query_OrdersConnection_Arguments(InternalContext context, Map<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
        super(context, data, graphQLInputObjectType);
    }

        public int getFirst() {
            return get("first");
        }

        public String getAfter() {
            return get("after");
        }

}