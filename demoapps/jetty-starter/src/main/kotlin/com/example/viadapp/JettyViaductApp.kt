@file:Suppress("ForbiddenImport")

package com.example.viadapp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.slf4j.LoggerFactory
import viaduct.service.BasicViaductFactory
import viaduct.service.TenantRegistrationInfo

class JettyViaductApp(private val port: Int = 8080) {
    private val server: Server

    init {
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        rootLogger.level = Level.INFO

        // Create a Viaduct engine using the BasicViaductFactory
        val viaduct = BasicViaductFactory.create(
            tenantRegistrationInfo = TenantRegistrationInfo(
                tenantPackagePrefix = "com.example.viadapp"
            )
        )

        // Create the servlets
        val viaductServlet = ViaductServlet(viaduct)
        val graphiqlServlet = GraphiQLServlet()

        // Set up Jetty server
        server = Server(port)
        val context = ServletContextHandler(ServletContextHandler.NO_SESSIONS)
        context.contextPath = "/"
        context.addServlet(ServletHolder(viaductServlet), "/graphql")
        context.addServlet(ServletHolder(graphiqlServlet), "/graphiql")

        server.handler = context
    }

    fun start() {
        server.start()
    }

    fun stop() {
        server.stop()
        server.destroy()
    }

    fun join() {
        server.join()
    }

    companion object {
        const val DEFAULT_PORT = 8080
    }
}

fun main(argv: Array<String>) {
    val port = System.getProperty("jetty.port")?.toIntOrNull() ?: JettyViaductApp.DEFAULT_PORT
    val app = JettyViaductApp(port)
    app.start()
    app.join()
}
