@file:Suppress("warnings")

package viaduct.api.grts

enum class Color : viaduct.api.types.Enum {
    RED,
    GREEN,
    BLUE;

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.Color> {
        override final val name = "Color"
        override final val kcls = viaduct.api.grts.Color::class
    }
}