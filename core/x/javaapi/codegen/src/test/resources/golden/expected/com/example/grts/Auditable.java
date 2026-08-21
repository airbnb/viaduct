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

@SuppressWarnings("MissingOverride")
public interface Auditable extends NodeCompositeOutput, Node, Timestamped {

    Type<Auditable> Reflection = Type.ofClass(Auditable.class);

    final class Fields implements TypeFields<Auditable> {
        private Fields() {}

        public static final Field<Auditable> __typename =
                Field.of("__typename", Reflection);
                public static final Field<Auditable> id =
                                Field.of("id", Reflection);

                public static final Field<Auditable> createdAt =
                                Field.of("createdAt", Reflection);

                public static final Field<Auditable> updatedAt =
                                Field.of("updatedAt", Reflection);

                public static final Field<Auditable> auditTrail =
                                Field.of("auditTrail", Reflection);

    }

        GlobalID<? extends Auditable> getIdOrThrow();
        GlobalID<? extends Auditable> getId();

        String getCreatedAtOrThrow();
        String getCreatedAt();

        String getUpdatedAtOrThrow();
        String getUpdatedAt();

        List<String> getAuditTrailOrThrow();
        List<String> getAuditTrail();

}