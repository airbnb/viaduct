@file:Suppress("warnings")

package viaduct.api.grts

import viaduct.apiannotations.InternalApi

@OptIn(InternalApi::class)
interface SearchHit : viaduct.api.types.Union {
  @OptIn(viaduct.apiannotations.InternalApi::class)
  object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.SearchHit> {
      override final val name = "SearchHit"
      override final val kcls = viaduct.api.grts.SearchHit::class
  }
  object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.SearchHit> {
          final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.SearchHit> =
              viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.SearchHit.Reflection)

  }

}