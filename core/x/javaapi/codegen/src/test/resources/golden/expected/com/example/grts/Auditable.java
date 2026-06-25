package com.example.grts;

import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.types.GraphQLInterface;
import viaduct.java.api.types.NodeCompositeOutput;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.List;

public interface Auditable extends NodeCompositeOutput, Node, Timestamped {

        GlobalID<? extends Auditable> getId();

        String getCreatedAt();

        String getUpdatedAt();

        List<String> getAuditTrail();

}