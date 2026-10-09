package viaduct.java.api.types;

import viaduct.apiannotations.StableApi;

/** Tagging interface for object types that implement the GraphQL Node interface. */
@StableApi
public interface NodeObject extends GraphQLObject, NodeCompositeOutput {}
