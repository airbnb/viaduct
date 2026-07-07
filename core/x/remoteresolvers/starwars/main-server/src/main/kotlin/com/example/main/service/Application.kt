package com.example.main.service

import io.micronaut.runtime.Micronaut

fun main(args: Array<String>) {
    Micronaut.build(*args)
        .packages("com.example.main", "com.example.starwars")
        .start()
}
