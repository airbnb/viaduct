package viaduct.api.select

import viaduct.apiannotations.StableApi

/**
 * [Selections] describes a String value in 1 of 2 forms:
 *
 * **FieldSet**
 *
 * A simple space-delimited set of fields, without surrounding braces.
 * A fieldset may not declare or use any fragments, though it may apply inline type conditions.
 *
 * Given schema:
 * ```
 * type Foo { id: ID, foo: Foo }
 * ```
 *
 * valid fieldset selections value on Foo include:
 *   - `id`
 *   - `id foo { id }`
 *   - `
 *       id
 *       foo {
 *         ... on Foo {
 *           id
 *         }
 *       }
 *     `
 *
 * **Document**
 *
 * A valid GraphQL document containing a fragment on a type
 * The document may define fragments and use any fragment that it defines.
 * If the document defines more than 1 fragment on a provided type, one of them must be
 * named "Main"
 *
 * Given schema:
 * ```
 * type Foo { id: ID, foo: Foo }
 * ```
 *
 *a valid Document selection on Foo is:
 * ```
 *   fragment Foo2 on Foo { id }
 *   fragment Main on Foo {
 *     id
 *     foo { ... Foo2 }
 *   }
 * ```
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.TYPE)
@StableApi
annotation class Selections
