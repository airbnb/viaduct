@file:Suppress("ForbiddenImport")

package com.example.viadapp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import viaduct.service.BasicViaductFactory
import viaduct.service.TenantRegistrationInfo
import viaduct.service.api.ExecutionInput

fun main(argv: Array<String>) {
    val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    rootLogger.level = Level.ERROR

    // Create a Viaduct engine using the BasicViaductFactory
    val viaduct = BasicViaductFactory.create(
        tenantRegistrationInfo = TenantRegistrationInfo(
            tenantPackagePrefix = "com.example.viadapp"
        )
    )

    // Create an execution input
    val executionInput = ExecutionInput.create(
        operationText = (
            argv.getOrNull(0)
                ?: """
                     query {
                         greeting
                     }
                """.trimIndent()
        ),
        variables = emptyMap(),
    )

    // Run the query
    val result = runBlocking {
        viaduct.execute(executionInput)
    }

    // [toSpecification] converts to JSON as described in the GraphQL
    // specification.
    val mapper = ObjectMapper().writerWithDefaultPrettyPrinter()
    println(
        mapper.writeValueAsString(result.toSpecification())
    )
}
