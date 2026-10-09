package viaduct.java.api.reflect;

import viaduct.apiannotations.StableApi;
import viaduct.java.api.types.GRT;

/**
 * Marker interface for the generated {@code Fields} descriptor container nested in a GRT.
 *
 * @param <T> the GRT whose fields are described
 */
@StableApi
public interface TypeFields<T extends GRT> {}
