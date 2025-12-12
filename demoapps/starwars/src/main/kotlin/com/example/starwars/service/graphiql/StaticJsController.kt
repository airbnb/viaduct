package com.example.starwars.service.graphiql

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces

@Controller("/js")
class StaticJsController {
    @Get("/jsx-loader.js")
    @Produces("application/javascript")
    fun jsxLoader(): HttpResponse<String> {
        val resource = this::class.java.classLoader.getResource("graphiql/js/jsx-loader.js")
        val content = resource?.readText() ?: return HttpResponse.notFound()
        return HttpResponse.ok(content).contentType("application/javascript")
    }

    @Get("/global-id-plugin.jsx")
    @Produces("application/javascript")
    fun globalIdPlugin(): HttpResponse<String> {
        val resource = this::class.java.classLoader.getResource("graphiql/js/global-id-plugin.jsx")
        val content = resource?.readText() ?: return HttpResponse.notFound()
        return HttpResponse.ok(content).contentType("application/javascript")
    }
}
