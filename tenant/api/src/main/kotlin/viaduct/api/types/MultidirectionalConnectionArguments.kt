package viaduct.api.types

import viaduct.apiannotations.ExperimentalApi

/**
 * Arguments supporting both forward and backward pagination.
 *
 * Combines [ForwardConnectionArguments] and [BackwardConnectionArguments]
 * for connections that support bidirectional traversal.
 */
@ExperimentalApi
interface MultidirectionalConnectionArguments :
    ForwardConnectionArguments, BackwardConnectionArguments
