package conventions

import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat(libs.findVersion("googleJavaFormat").get().requiredVersion)
    }
}

dependencies {
    add("errorprone", libs.findLibrary("errorprone-core").get())
    add("errorprone", libs.findLibrary("nullaway").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Werror")
    options.errorprone {
        error("NullAway")
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:JSpecifyMode", "true")
        option("NullAway:HandleTestAssertionLibraries", "true")
    }
}
