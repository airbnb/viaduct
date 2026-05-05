plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

dependencies {
    implementation(libs.viaduct.shared.apiannotations)
}

viaductPublishing {
    name.set("Tenant Validation")
    description.set("Validation interfaces shared across Viaduct tenant annotation processors")
}
