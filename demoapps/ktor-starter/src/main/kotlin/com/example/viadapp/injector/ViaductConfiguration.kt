package com.example.viadapp.injector

import com.example.viadapp.SCHEMA_ID
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaRegistrationInfo
import viaduct.service.SchemaScopeInfo
import viaduct.service.api.Viaduct

object ViaductConfiguration {
    val viaductService: Viaduct by lazy {
        BasicViaductFactory.createFromResource(
            schemaRegistrationInfo = SchemaRegistrationInfo(
                scopes = listOf(SchemaScopeInfo(SCHEMA_ID)),
            ),
        )
    }
}
