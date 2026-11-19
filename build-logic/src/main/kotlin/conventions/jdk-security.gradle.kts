package conventions

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
}

// Validate the actual selected compiler/launcher, including per-task overrides and testJavaVersion.
// Check at execution time so unused toolchains are not provisioned during configuration.
tasks.withType<JavaCompile>().configureEach {
    doFirst {
        val jdk = javaCompiler.get().metadata
        requirePatchedJdk(jdk.javaRuntimeVersion, jdk.installationPath.toString())
    }
}
tasks.withType<Test>().configureEach {
    doFirst {
        val jdk = javaLauncher.get().metadata
        requirePatchedJdk(jdk.javaRuntimeVersion, jdk.installationPath.toString())
    }
}
tasks.withType<JavaExec>().configureEach {
    doFirst {
        val jdk = javaLauncher.get().metadata
        requirePatchedJdk(jdk.javaRuntimeVersion, jdk.installationPath.toString())
    }
}

// Kotlin's jvmToolchain configures the Java extension as well. The compiler daemon's separate
// JVM is also protected by kotlin.daemon.jvmargs in the synchronized gradle.properties files.
val kotlinJdk = javaToolchains.launcherFor(java.toolchain)
tasks.withType<KotlinCompile>().configureEach {
    val compilerJdk = kotlinJdk
    doFirst {
        val jdk = compilerJdk.get().metadata
        requirePatchedJdk(jdk.javaRuntimeVersion, jdk.installationPath.toString())
    }
}
