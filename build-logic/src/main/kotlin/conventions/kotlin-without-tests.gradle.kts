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
        // graphql-java 26 added @NullMarked (jspecify) annotations, causing hundreds of
        // nullability warnings in Kotlin 1.9. Ignore them until we upgrade to Kotlin 2.x
        // which handles jspecify annotations natively.
        freeCompilerArgs.add("-Xjspecify-annotations=ignore")
    }
}
