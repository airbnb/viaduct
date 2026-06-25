package com.example.grts;

import viaduct.java.api.globalid.GlobalID;
import viaduct.java.api.types.GraphQLInterface;
import viaduct.java.api.types.NodeCompositeOutput;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetTime;
import java.util.List;

public interface Node extends NodeCompositeOutput {

        GlobalID<? extends Node> getId();

}