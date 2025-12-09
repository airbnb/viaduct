package com.example.starwars.service.graphiql

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces

@Controller("/js")
class StaticJsController {
    @Get("/jsx-loader.js")
    @Produces("application/javascript")
    fun jsxLoader(): HttpResponse<String> = serveJs("jsx-loader.js")

    @Get("/global-id-plugin.jsx")
    @Produces("application/javascript")
    fun globalIdPlugin(): HttpResponse<String> = serveJs("global-id-plugin.jsx")

    private fun serveJs(filename: String): HttpResponse<String> {
        val resource = this::class.java.classLoader.getResource("graphiql/js/$filename")
            ?: return HttpResponse.notFound()
        return HttpResponse.ok(resource.readText()).contentType("application/javascript")
    }
}
