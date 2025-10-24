package com.example.viadapp.resolvers

import viaduct.api.TenantModule

class HelloWorldTenantModule : TenantModule {
    override val metadata: Map<String, String> = mapOf(
        "name" to "HelloWorldTenantModule"
    )
}
