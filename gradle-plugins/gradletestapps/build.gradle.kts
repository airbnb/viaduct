plugins {
    id("buildroot.orchestration")
}

allprojects {
    providers.gradleProperty("viaductFixtureBuildRoot").orNull?.let { root ->
        layout.buildDirectory.set(file(root).resolve(project.path.replace(':', '/').trim('/')).resolve("build"))
    }
}
