package viaduct.mapping.graphql

import org.junit.jupiter.api.Assertions.assertEquals

internal fun <From, To> assertRoundtrip(
    conv: Conv<From, To>,
    from: From,
    to: To
) {
    assertEquals(to, conv(from))
    assertEquals(from, conv.invert(to))
}
