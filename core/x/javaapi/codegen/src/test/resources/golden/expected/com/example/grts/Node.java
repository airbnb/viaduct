package com.example.grts;

import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.reflect.CompositeField;
import viaduct.java.api.reflect.Field;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.reflect.TypeFields;
import viaduct.java.api.types.GraphQLInterface;
import viaduct.java.api.types.NodeCompositeOutput;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.List;

public interface Node extends NodeCompositeOutput {

    Type<Node> Reflection = Type.ofClass(Node.class);

    final class Fields implements TypeFields<Node> {
        private Fields() {}

        public static final Field<Node> __typename =
                Field.of("__typename", Reflection);
                public static final Field<Node> id =
                                Field.of("id", Reflection);

    }

        GlobalID<? extends Node> getId();

}