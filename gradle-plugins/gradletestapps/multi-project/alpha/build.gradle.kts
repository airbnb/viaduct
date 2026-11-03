import viaduct.gradle.task.GenerateResolverBasesTask
plugins {
    id("conventions.kotlin")
    id("conventions.ksp")
    id("com.airbnb.viaduct.module-gradle-plugin")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
}

configurations.consumable("fixtureResolverBases") {
    outgoing.artifact(tasks.named<GenerateResolverBasesTask>("generateViaductResolverBases").flatMap { it.outputDirectory })
}
