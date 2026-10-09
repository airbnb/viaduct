package viaduct.java.api.types;

import viaduct.apiannotations.StableApi;

/**
 * Tagging interface for GraphQL object types. Renamed from "Object" to avoid confusion with
 * java.lang.Object.
 */
@StableApi
public interface GraphQLObject extends GraphQLInterface {}
