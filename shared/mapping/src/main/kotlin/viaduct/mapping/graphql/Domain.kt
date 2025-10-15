package viaduct.mapping.graphql

import viaduct.utils.bijection.Bijection

/**
 * A [Domain] models a family of value types that can participate in object mapping.
 *
 * @param ObjectType a type that can be transformed into a [IR.Value.Object] representation.
 * @see IR
 */
interface Domain<ObjectType> {
    /**
     * A reversible conversion between objects in this domain to objects in the IR domain.
     *
     * A valid Bijection for this Domain should be able to pass the `validateDomain` check in DomainValidator.kt
     */
    fun objectToIR(): Bijection<ObjectType, IR.Value.Object>

    /** Return a reversible conversion between objects in this domain and the target domain */
    infix fun <T> map(target: Domain<T>): Bijection<ObjectType, T> = objectToIR() andThen target.objectToIR().inverse()
}
