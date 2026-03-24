package viaduct.api

import viaduct.apiannotations.StableApi

/**
 * Marker interface for node resolvers that vary their response based on the requested selection set.
 *
 * Implementing this interface:
 * - Enables access to `ctx.selections()` within resolver methods
 * - Changes caching behavior to match by ID + selection set (instead of ID-only)
 *
 * Use when the resolver needs to optimize by fetching only requested fields.
 * Don't use if the resolver always returns the same data for a given ID.
 */
@StableApi
interface SelectiveResolver
