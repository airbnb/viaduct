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
public class Mutation_CreateOrder_Arguments extends InputBase implements Arguments {

    public static final Type<Mutation_CreateOrder_Arguments> Reflection = Type.ofClass(Mutation_CreateOrder_Arguments.class);

    public static final class Fields implements TypeFields<Mutation_CreateOrder_Arguments> {
        private Fields() {}

        public static final Field<Mutation_CreateOrder_Arguments> __typename =
                Field.of("__typename", Reflection);
                public static final CompositeField<Mutation_CreateOrder_Arguments, CreateOrderInput> input =
                                CompositeField.of("input", Reflection, CreateOrderInput.Reflection);

    }

    // Public because the framework constructs arguments reflectively across packages
    // (JavaFieldResolverExecutorImpl, VariablesProviderExecutorImpl, etc.). @InternalApi
    // marks it as not-for-tenant-use, mirroring Kotlin's `internal constructor`.
    @InternalApi
    public Mutation_CreateOrder_Arguments(InternalContext context, Map<String, Object> data, GraphQLInputObjectType graphQLInputObjectType) {
        super(context, data, graphQLInputObjectType);
    }

    /**
     * Returns whether {@code field} was explicitly provided, including an explicit
     * {@code null}.
     *
     * <p>This is meaningful only for top-level fields. graphql-java applies input
     * coercion, including default values, to nested input objects, so presence cannot
     * be determined for fields nested more deeply than this input.
     */
    public boolean isPresent(Field<Mutation_CreateOrder_Arguments> field) {
        return isFieldPresent(field);
    }

        public CreateOrderInput getInput() {
            return getInput("input", CreateOrderInput::new);
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

                public Builder input(CreateOrderInput input) {
                    data.put("input", input);
        return this;
                }


        public Mutation_CreateOrder_Arguments build() {
            return new Mutation_CreateOrder_Arguments(__context, new LinkedHashMap<>(data), null);
        }
    }
}