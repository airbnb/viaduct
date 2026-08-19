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

public class PageInfo extends ObjectBase {

    public static final Type<PageInfo> Reflection = Type.ofClass(PageInfo.class);

    public static final class Fields implements TypeFields<PageInfo> {
        private Fields() {}

        public static final Field<PageInfo> __typename =
                Field.of("__typename", Reflection);
                public static final Field<PageInfo> hasNextPage =
                                Field.of("hasNextPage", Reflection);

                public static final Field<PageInfo> hasPreviousPage =
                                Field.of("hasPreviousPage", Reflection);

                public static final Field<PageInfo> startCursor =
                                Field.of("startCursor", Reflection);

                public static final Field<PageInfo> endCursor =
                                Field.of("endCursor", Reflection);

    }

    public PageInfo(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private PageInfo(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }

    public PageInfo(InternalContext context, RootFieldReference rootFieldReference) {
        super(context, rootFieldReference);
    }
        public boolean getHasNextPage() {
            return fetchScalar("hasNextPage");
        }

        public boolean getHasPreviousPage() {
            return fetchScalar("hasPreviousPage");
        }

        public String getStartCursor() {
            return fetchScalar("startCursor");
        }

        public String getEndCursor() {
            return fetchScalar("endCursor");
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

                public Builder hasNextPage(boolean hasNextPage) {
                    hasNextPage = OutputBuilderTypeChecker.checkField(
                            __context,
                            "PageInfo",
                            "hasNextPage",
                            null,
                            hasNextPage);
                    data.put("hasNextPage", hasNextPage);
        return this;
                }

                public Builder hasPreviousPage(boolean hasPreviousPage) {
                    hasPreviousPage = OutputBuilderTypeChecker.checkField(
                            __context,
                            "PageInfo",
                            "hasPreviousPage",
                            null,
                            hasPreviousPage);
                    data.put("hasPreviousPage", hasPreviousPage);
        return this;
                }

                public Builder startCursor(String startCursor) {
                    startCursor = OutputBuilderTypeChecker.checkField(
                            __context,
                            "PageInfo",
                            "startCursor",
                            null,
                            startCursor);
                    data.put("startCursor", startCursor);
        return this;
                }

                public Builder endCursor(String endCursor) {
                    endCursor = OutputBuilderTypeChecker.checkField(
                            __context,
                            "PageInfo",
                            "endCursor",
                            null,
                            endCursor);
                    data.put("endCursor", endCursor);
        return this;
                }


        public PageInfo build() {
            return new PageInfo(__context, new LinkedHashMap<>(data));
        }
    }
}