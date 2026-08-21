@file:Suppress("warnings")

package viaduct.api.grts

interface Auditable : viaduct.api.types.Interface, viaduct.api.types.NodeCompositeOutput, viaduct.api.grts.Node, viaduct.api.grts.Timestamped {
          fun getAuditTrailOrThrow(alias: String?): kotlin.collections.List<kotlin.String>?
          fun getAuditTrailOrThrow(): kotlin.collections.List<kotlin.String>?
          fun getAuditTrail(alias: String?): kotlin.collections.List<kotlin.String>?
          fun getAuditTrail(): kotlin.collections.List<kotlin.String>?
          fun getAuditTrailOrNull(alias: String?): kotlin.collections.List<kotlin.String>?
          fun getAuditTrailOrNull(): kotlin.collections.List<kotlin.String>?


    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.Auditable> {
        override final val name = "Auditable"
        override final val kcls = viaduct.api.grts.Auditable::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.Auditable> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.Auditable> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.Auditable.Reflection)

            final val id: viaduct.api.reflect.Field<viaduct.api.grts.Auditable> =
                viaduct.api.internal.FieldImpl("id", viaduct.api.grts.Auditable.Reflection)

            final val createdAt: viaduct.api.reflect.Field<viaduct.api.grts.Auditable> =
                viaduct.api.internal.FieldImpl("createdAt", viaduct.api.grts.Auditable.Reflection)

            final val updatedAt: viaduct.api.reflect.Field<viaduct.api.grts.Auditable> =
                viaduct.api.internal.FieldImpl("updatedAt", viaduct.api.grts.Auditable.Reflection)

            final val auditTrail: viaduct.api.reflect.Field<viaduct.api.grts.Auditable> =
                viaduct.api.internal.FieldImpl("auditTrail", viaduct.api.grts.Auditable.Reflection)

    }

}