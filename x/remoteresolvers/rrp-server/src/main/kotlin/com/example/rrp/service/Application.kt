package com.example.rrp.service

import io.micronaut.runtime.Micronaut

fun main(args: Array<String>) {
    Micronaut.build(*args)
        .packages("com.example.rrp", "com.example.starwars")
        .start()
}
