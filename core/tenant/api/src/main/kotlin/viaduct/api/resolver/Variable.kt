package viaduct.api.resolver

import viaduct.apiannotations.StableApi

/**
 * @property name the name of the variable being defined
 * @property fromObjectField a path into a selection set of the field to which this annotation is applied.
 *
 *  For example, given a [Resolver] annotation with this `objectValueFragment`:
 *  ```graphql
 *  {
 *    foo(x: 2) {
 *       myBar: bar {
 *         y
 *       }
 *    }
 *  }
 *  ```
 *  Then a "foo.myBar.y" [fromObjectField] value will bind the value of the "y" selection to a variable
 *  named [name].
 *
 *  The value of [fromObjectField] is subject to these requirements:
 *
 *  1. the path must be a path that is selected in the selection set this [Variable] is bound to
 *  1. the path must terminate on a scalar or enum value, or a potentially-nested
 *     list that wraps one of these values
 *  1. the path may not traverse through list-valued fields
 *  1. the type at the end of the path must be compatible with the location that this variable is used.
 *     For example, if the variable location is non-nullable and has no default value, then the path may
 *     not traverse through nullable selections, or selections with variable @skip/@include conditions,
 *     or fragments with narrowing type conditions
 *
 * @property fromQueryField see [fromObjectField]
 *
 * @property fromArgument a path into a GraphQL argument of the field to which this annotation
 *  is applied.
 *
 *  Example:
 *  ```
 *  name = "x"
 *  fromArgument = "foo"
 *  ```
 *  Will bind the value from the "foo" argument of the dependent field to a variable named "x"
 *
 *  The value of [fromArgument] may be a dot-separated path, in which case the path will be
 *  traversed to select a value to bind to [name].
 *  This path may not traverse through lists, though it may terminate on a list or input
 *  object type.
 *  If any value in the traversal path is null, then the final extracted value will be null.
 *  If a supplied value for a traversal step is missing but a default value is defined for the
 *  argument or input field, then the default value will be used.
 *
 *  Example:
 *  ```
 *  name = "x"
 *  fromArgument = "foo.bar"
 *  ```
 *  Will traverse through the "foo" field of the dependent field, which must be an input type, and
 *  will bind the value of the "bar" input field to a variable named "x".
 *
 *  In all cases, the schema type of the argument indicated by this property must be coercible to
 *  the type in which this variable is used.
 */
@StableApi
annotation class Variable(
    val name: String,
    val fromObjectField: String = UNSET_STRING_VALUE,
    val fromQueryField: String = UNSET_STRING_VALUE,
    val fromArgument: String = UNSET_STRING_VALUE
) {
    @StableApi
    companion object {
        const val UNSET_STRING_VALUE = "XY!#* N0T S3T!"
    }
}
