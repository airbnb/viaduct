plugins {
    `kotlin-dsl`
}

repositories {
    val mirror = System.getenv("VIADUCT_ARTIFACTORY_MIRROR")
    if (mirror != null) maven { url = uri(mirror) } else mavenCentral()
}
