package viaduct.api.types

import viaduct.apiannotations.ExperimentalApi

/**
 * Base interface for connection pagination arguments.
 *
 * @see ForwardConnectionArguments
 * @see BackwardConnectionArguments
 * @see MultidirectionalConnectionArguments
 */
@ExperimentalApi
interface ConnectionArguments : Arguments
