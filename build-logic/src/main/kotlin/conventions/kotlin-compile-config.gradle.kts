package conventions

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        optIn.add("viaduct.apiannotations.ExperimentalApi")
        optIn.add("viaduct.apiannotations.InternalApi")
        optIn.add("viaduct.apiannotations.TestingApi")
    }
}
