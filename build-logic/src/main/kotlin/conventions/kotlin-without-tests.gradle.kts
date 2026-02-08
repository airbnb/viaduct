package conventions

import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    idea
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_1_8
        languageVersion = KotlinVersion.KOTLIN_1_8
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}
