package com.example.grts;

import viaduct.engine.api.EngineObjectData;
import viaduct.engine.api.NodeReference;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.internal.NodeObjectBase;
import viaduct.java.api.internal.ObjectBase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class User extends NodeObjectBase implements Node, SearchHit {

    public User(InternalContext context, EngineObjectData.Sync data) {
        super(context, data);
    }

    private User(InternalContext context, Map<String, Object> data) {
        super(context, data);
    }

    public User(InternalContext context, NodeReference nodeReference) {
        super(context, nodeReference);
    }

        public GlobalID<User> getId() {
            return fetchGlobalID("id");
        }

        public String getName() {
            return fetchScalar("name");
        }

        public String getNickname() {
            return fetchScalar("nickname");
        }

        public Integer getAge() {
            return fetchScalar("age");
        }

        public boolean getActive() {
            return fetchScalar("active");
        }

        public Color getFavoriteColor() {
            return fetchEnum("favoriteColor", Color.class);
        }

        public List<Integer> getScores() {
            return fetchScalarList("scores");
        }

        public GlobalID<Order> getLastOrder() {
            return fetchGlobalID("lastOrder");
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

                public Builder id(GlobalID<User> id) {
                    data.put("id", id == null ? null : __context.getGlobalIDCodec().serialize(id.getType().getName(), id.getInternalID()));
        return this;
                }

                public Builder name(String name) {
                    data.put("name", name);
        return this;
                }

                public Builder nickname(String nickname) {
                    data.put("nickname", nickname);
        return this;
                }

                public Builder age(Integer age) {
                    data.put("age", age);
        return this;
                }

                public Builder active(boolean active) {
                    data.put("active", active);
        return this;
                }

                public Builder favoriteColor(Color favoriteColor) {
                    data.put("favoriteColor", favoriteColor);
        return this;
                }

                public Builder scores(List<Integer> scores) {
                    data.put("scores", scores);
        return this;
                }

                public Builder lastOrder(GlobalID<Order> lastOrder) {
                    data.put("lastOrder", lastOrder == null ? null : __context.getGlobalIDCodec().serialize(lastOrder.getType().getName(), lastOrder.getInternalID()));
        return this;
                }


        public User build() {
            return new User(__context, new LinkedHashMap<>(data));
        }
    }
}