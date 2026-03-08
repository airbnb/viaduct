plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.airbnb.viaduct.build"

fun findVersionFile(start: File): File {
    var d: File? = start
    while (d != null) {
        val f = File(d, "VERSION")
        if (f.exists()) return f
        d = d.parentFile
    }
    error("VERSION file not found starting from: $start")
}

version = findVersionFile(rootDir).readText().trim()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

