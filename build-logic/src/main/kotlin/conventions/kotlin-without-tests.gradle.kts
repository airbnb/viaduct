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
    // Treat Kotlin compiler warnings as errors (matches the Bazel -Werror) for every module applying
    // a Viaduct Kotlin convention — core, gradletestapps, remoteresolvers. :tenant:tutorials stays
    // lenient as teaching/example code. (gradle-plugins use conventions.gradle-plugin-kotlin, which
    // sets this separately, since they omit this convention's 1.8 pinning.)
    val treatWarningsAsErrors = project.path != ":tenant:tutorials"
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_1_8
        languageVersion = KotlinVersion.KOTLIN_1_8
        // graphql-java 26 added @NullMarked (jspecify) annotations, causing hundreds of
        // nullability warnings in Kotlin 1.9. Ignore them until we upgrade to Kotlin 2.x
        // which handles jspecify annotations natively.
        freeCompilerArgs.add("-Xjspecify-annotations=ignore")
        allWarningsAsErrors = treatWarningsAsErrors
    }
}
