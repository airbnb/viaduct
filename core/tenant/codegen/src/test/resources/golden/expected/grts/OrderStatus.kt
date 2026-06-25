@file:Suppress("warnings")

package viaduct.api.grts

enum class OrderStatus : viaduct.api.types.Enum {
    PENDING,
    CONFIRMED,
    COMPLETED,
    CANCELLED;

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.OrderStatus> {
        override final val name = "OrderStatus"
        override final val kcls = viaduct.api.grts.OrderStatus::class
    }
}