package detekt

val detektPluginsCfg = configurations.maybeCreate("detektPlugins")
dependencies {
    add(detektPluginsCfg.name, "com.airbnb.viaduct:build-common")
}
