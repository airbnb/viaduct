package viaduct.arbitrary.graphql

/** An enumeration of GraphQL object flavors */
internal enum class TypeType {
    Interface,
    Object,
    Input,
    Union,
    Scalar,
    Enum,
    Directive
}
