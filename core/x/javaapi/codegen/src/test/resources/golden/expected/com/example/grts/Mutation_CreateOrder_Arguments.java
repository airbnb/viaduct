package com.example.grts;

import graphql.schema.GraphQLInputObjectType;
import viaduct.java.api.globalid.GlobalID;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.List;
import java.util.Map;
import viaduct.java.api.types.Arguments;
import viaduct.java.api.internal.InputBase;
import viaduct.java.api.internal.InternalContext;

/** Generated arguments class for resolver field. */
public class Mutation_CreateOrder_Arguments extends InputBase implements Arguments {

    public Mutation_CreateOrder_Arguments(InternalContext context, Map<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
        super(context, data, graphQLInputObjectType);
    }

        public CreateOrderInput getInput() {
            return getInput("input", CreateOrderInput::new);
        }

}