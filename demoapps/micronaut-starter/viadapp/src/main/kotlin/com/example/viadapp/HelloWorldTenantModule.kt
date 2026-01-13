package com.example.viadapp

import viaduct.api.TenantModule

class HelloWorldTenantModule : TenantModule {
    override val metadata: Map<String, String> = mapOf(
        "name" to "HelloWorldTenantModule"
    )
}
