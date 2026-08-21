@file:Suppress("warnings")

package viaduct.api.grts

interface Timestamped : viaduct.api.types.Interface {
          fun getCreatedAtOrThrow(alias: String?): kotlin.String
          fun getCreatedAtOrThrow(): kotlin.String
          fun getCreatedAt(alias: String?): kotlin.String
          fun getCreatedAt(): kotlin.String
          fun getCreatedAtOrNull(alias: String?): kotlin.String?
          fun getCreatedAtOrNull(): kotlin.String?

          fun getUpdatedAtOrThrow(alias: String?): kotlin.String?
          fun getUpdatedAtOrThrow(): kotlin.String?
          fun getUpdatedAt(alias: String?): kotlin.String?
          fun getUpdatedAt(): kotlin.String?
          fun getUpdatedAtOrNull(alias: String?): kotlin.String?
          fun getUpdatedAtOrNull(): kotlin.String?


    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.Timestamped> {
        override final val name = "Timestamped"
        override final val kcls = viaduct.api.grts.Timestamped::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.Timestamped> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.Timestamped> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.Timestamped.Reflection)

            final val createdAt: viaduct.api.reflect.Field<viaduct.api.grts.Timestamped> =
                viaduct.api.internal.FieldImpl("createdAt", viaduct.api.grts.Timestamped.Reflection)

            final val updatedAt: viaduct.api.reflect.Field<viaduct.api.grts.Timestamped> =
                viaduct.api.internal.FieldImpl("updatedAt", viaduct.api.grts.Timestamped.Reflection)

    }

}