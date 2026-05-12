@file:OptIn(InternalApi::class)

package com.example.viadapp.resolvers

import viaduct.api.TenantModule
import viaduct.apiannotations.InternalApi

class HelloWorldTenantModule : TenantModule {
    override val metadata: Map<String, String> = mapOf(
        "name" to "HelloWorldTenantModule"
    )
}
