package com.example.starwars.service

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan

@SpringBootApplication
@ComponentScan(basePackages = ["com.example.starwars"])
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
