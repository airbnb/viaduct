package viaduct.mapping.graphql

/**
 * A [Domain] models a family of value types that can participate in object mapping.
 *
 * @param ObjectType a type that can be transformed into a [IR.Value.Object] representation.
 * @see IR
 */
interface Domain<ObjectType> {
    /**
     * A conversion between values in this domain to values in the IR domain.
     *
     * A valid [Conv] for this Domain should be able to pass the `validateDomain` check in DomainValidator.kt
     */
    val conv: Conv<ObjectType, IR.Value.Object>

    /**
     * Create a mapper from this domain to the target domain.
     *
     * The mapper converts values from this domain's [ObjectType] to the target domain's object type
     * by going through the intermediate [IR] representation.
     */
    fun <T> mapperTo(target: Domain<T>): Conv<ObjectType, T> = conv andThen target.conv.inverse()
}
