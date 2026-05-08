plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

viaductPublishing {
    name.set("Tenant Validation")
    description.set("Validation interfaces shared across Viaduct tenant annotation processors")
}

dependencies {
    implementation(libs.viaduct.shared.apiannotations)
}
