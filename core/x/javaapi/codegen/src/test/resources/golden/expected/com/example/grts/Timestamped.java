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

public interface Timestamped extends GraphQLInterface {

    Type<Timestamped> Reflection = Type.ofClass(Timestamped.class);

    final class Fields implements TypeFields<Timestamped> {
        private Fields() {}

        public static final Field<Timestamped> __typename =
                Field.of("__typename", Reflection);
                public static final Field<Timestamped> createdAt =
                                Field.of("createdAt", Reflection);

                public static final Field<Timestamped> updatedAt =
                                Field.of("updatedAt", Reflection);

    }

        String getCreatedAt();

        String getUpdatedAt();

}