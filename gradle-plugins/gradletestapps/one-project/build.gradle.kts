plugins {
    id("conventions.kotlin")
    id("conventions.ksp")
    id("com.airbnb.viaduct.application-gradle-plugin")
    id("com.airbnb.viaduct.module-gradle-plugin")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
    implementation("com.airbnb.viaduct:runtime")
}

val moduleJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val schemaJar = tasks.named<Jar>("generateViaductSchemaResources").flatMap { it.archiveFile }

tasks.withType<Test>().configureEach {
    systemProperty("projectBuildDir", layout.buildDirectory.asFile.get().absolutePath)
    systemProperty("moduleJar", moduleJar.get().asFile.absolutePath)
    systemProperty("schemaJar", schemaJar.get().asFile.absolutePath)
    inputs.files(moduleJar, schemaJar)
}
