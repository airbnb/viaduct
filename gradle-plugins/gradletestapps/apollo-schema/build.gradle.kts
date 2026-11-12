import viaduct.gradle.ViaductApplicationConventions
import viaduct.gradle.apollo.GenerateApolloSchema

plugins {
    id("conventions.kotlin")
}

dependencies {
    testImplementation("com.airbnb.viaduct:api")
    testImplementation("com.airbnb.viaduct:runtime")
}

tasks.named<GenerateApolloSchema>("generateApolloSchema") {
    keyDirectiveName.set("apollo__key")
}

val viaduct = extensions.getByType<ViaductApplicationConventions>()
viaduct.pluginSchema.into("dev.example.apollo/source") {
    from(layout.projectDirectory.file("directives.graphqls"))
}

val grts = tasks.named<Jar>("generateViaductGRTs").flatMap { it.archiveFile }
tasks.withType<Test>().configureEach {
    inputs.dir(viaduct.finalSchemaDirectory).withPropertyName("finalSchema").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(grts).withPropertyName("centralGrts").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("projectBuildDir", layout.buildDirectory.get().asFile.absolutePath)
    systemProperty("centralGrts", grts.get().asFile.absolutePath)
}
