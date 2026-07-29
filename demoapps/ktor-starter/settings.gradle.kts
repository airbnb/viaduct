// When part of composite build, use local gradle-plugins
// When standalone, use Maven Central (only after version is published)
pluginManagement {
    val viaductVersion: String by settings

    if (gradle.parent != null) {
        includeBuild("../../gradle-plugins")
    } else {
        repositories {
            if (System.getenv("USE_MAVEN_LOCAL")?.toBoolean() == true) mavenLocal()
            if (System.getenv("USE_VIADUCT_SNAPSHOT_REPO")?.toBoolean() == true) {
                maven("https://central.sonatype.com/repository/maven-snapshots/")
            }
            System.getenv("VIADUCT_ARTIFACTORY_MIRROR")?.let { maven { url = uri(it) } }
            gradlePluginPortal()
        }
    }
    plugins {
        id("com.airbnb.viaduct.settings-gradle-plugin") version viaductVersion
    }
}

plugins {
    id("com.airbnb.viaduct.settings-gradle-plugin")
}

rootProject.name = "viaduct-ktor-starter"

val viaductVersion: String by settings

dependencyResolutionManagement {
    repositories {
        if (System.getenv("USE_MAVEN_LOCAL")?.toBoolean() == true) mavenLocal()
        if (System.getenv("USE_VIADUCT_SNAPSHOT_REPO")?.toBoolean() == true) {
            maven("https://central.sonatype.com/repository/maven-snapshots/")
        }
        System.getenv("VIADUCT_ARTIFACTORY_MIRROR")?.let { maven { url = uri(it) } }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("gradle/viaduct.versions.toml"))
            version("viaduct", viaductVersion)
            // Allow CI to override Kotlin/KSP versions for matrix testing without
            // touching the toml.  The two vars must be set together — a mismatched
            // pair (e.g. Kotlin 2.2 with a KSP 2.1 prefix) will fail at build time
            // with a confusing error, so we catch the mismatch here instead.
            // See demoapps/ksp-versioning.md for the supported (kotlin, ksp) pairs.
            val kotlinVer = System.getenv("KOTLIN_VERSION")
            val kspVer    = System.getenv("KSP_VERSION")
            require((kotlinVer == null) == (kspVer == null)) {
                "KOTLIN_VERSION and KSP_VERSION must be set together (got " +
                "KOTLIN_VERSION=$kotlinVer, KSP_VERSION=$kspVer)"
            }
            kotlinVer?.let { version("kotlin", it) }
            kspVer?.let    { version("ksp",    it) }
        }
    }
}

includeViaductApplication {
    project(":")
    modulePackagePrefix("com.example.viadapp")

    includeModule {
        project(":resolvers")
        modulePackageSuffix("resolvers")
    }
}
