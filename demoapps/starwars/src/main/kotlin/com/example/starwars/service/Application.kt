package com.example.starwars.service

import io.micronaut.runtime.Micronaut

fun main(args: Array<String>) {
    Micronaut.run(Application::class.java, *args)
}

class Application
