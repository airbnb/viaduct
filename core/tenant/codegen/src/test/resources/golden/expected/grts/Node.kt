@file:Suppress("warnings")

package viaduct.api.grts

interface Node : viaduct.api.types.Interface, viaduct.api.types.NodeCompositeOutput {
          fun getIdOrThrow(alias: String?): viaduct.api.globalid.GlobalID<viaduct.api.grts.Node>
          fun getIdOrThrow(): viaduct.api.globalid.GlobalID<viaduct.api.grts.Node>
          fun getId(alias: String?): viaduct.api.globalid.GlobalID<viaduct.api.grts.Node>
          fun getId(): viaduct.api.globalid.GlobalID<viaduct.api.grts.Node>
          fun getIdOrNull(alias: String?): viaduct.api.globalid.GlobalID<viaduct.api.grts.Node>?
          fun getIdOrNull(): viaduct.api.globalid.GlobalID<viaduct.api.grts.Node>?


    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.Node> {
        override final val name = "Node"
        override final val kcls = viaduct.api.grts.Node::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.Node> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.Node> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.Node.Reflection)

            final val id: viaduct.api.reflect.Field<viaduct.api.grts.Node> =
                viaduct.api.internal.FieldImpl("id", viaduct.api.grts.Node.Reflection)

    }

}