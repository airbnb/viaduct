import java.net.URL

plugins {
    id("conventions.kotlin")
    `maven-publish`
}

tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

val emptyJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(tasks["sourcesJar"])
            artifact(emptyJavadocJar.get())

            groupId = "com.airbnb.viaduct"
            artifactId = "devserve-runtime"

            pom {
                name.set("Viaduct DevServe Runtime")
                description.set("Development server runtime for Viaduct GraphQL applications with GraphiQL IDE")
                url.set("https://airbnb.io/viaduct/")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("airbnb")
                        name.set("Airbnb, Inc.")
                        email.set("viaduct-maintainers@airbnb.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/airbnb/viaduct.git")
                    developerConnection.set("scm:git:ssh://github.com/airbnb/viaduct.git")
                    url.set("https://github.com/airbnb/viaduct")
                }
            }
        }
    }
}

dependencies {
    // Viaduct dependencies
    implementation(libs.viaduct.service.api)
    implementation(libs.viaduct.service.wiring)
    implementation(libs.viaduct.tenant.api)

    // GraphQL
    implementation(libs.graphql.java)

    // Ktor server
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-netty:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-server-cors:3.0.3")
    implementation("io.ktor:ktor-server-websockets:3.0.3")
    implementation("io.ktor:ktor-serialization-jackson:3.0.3")

    // Classpath scanning for finding @ViaductApplication
    implementation(libs.classgraph)

    // Logging
    implementation(libs.slf4j.api)
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Kotlin
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core.jvm)

    // JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.engine)
}

/**
 * Task to generate GraphiQL HTML file.
 * Creates a GraphiQL IDE page that loads the latest version from CDN.
 */
val generateGraphiQL by tasks.registering {
    group = "build"
    description = "Generate GraphiQL HTML for the development server"

    val outputDir = layout.buildDirectory.dir("resources/main/graphiql")
    val outputFile = outputDir.map { it.file("index.html") }

    outputs.file(outputFile)
    outputs.cacheIf { true }

    doLast {
        logger.lifecycle("Generating GraphiQL HTML...")

        val graphiqlHtml = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>GraphiQL - Viaduct DevServe</title>
    <style>
        body {
            margin: 0;
            overflow: hidden;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
        }
        #graphiql {
            height: 100vh;
        }
    </style>
    <link rel="stylesheet" href="https://unpkg.com/graphiql/graphiql.min.css" />
</head>
<body>
    <div id="graphiql">Loading GraphiQL...</div>

    <script
        crossorigin
        src="https://unpkg.com/react@18/umd/react.production.min.js"
    ></script>
    <script
        crossorigin
        src="https://unpkg.com/react-dom@18/umd/react-dom.production.min.js"
    ></script>
    <script
        crossorigin
        src="https://unpkg.com/graphiql/graphiql.min.js"
    ></script>

    <script>
        const root = ReactDOM.createRoot(document.getElementById('graphiql'));

        // Create fetcher with proper error handling
        const fetcher = GraphiQL.createFetcher({
            url: '/graphql',
        });

        // Log when schema is loaded
        const originalFetcher = fetcher;
        const loggingFetcher = async (graphQLParams) => {
            try {
                const result = await originalFetcher(graphQLParams);
                // Log introspection queries for debugging
                if (graphQLParams.operationName === 'IntrospectionQuery') {
                    console.log('Schema introspection successful');
                }
                return result;
            } catch (error) {
                console.error('GraphQL request failed:', error);
                throw error;
            }
        };

        root.render(
            React.createElement(GraphiQL, {
                fetcher: loggingFetcher,
                defaultQuery: '# Welcome to Viaduct DevServe!\\n# \\n# Start typing your GraphQL query here.\\n# Press Ctrl+Space for autocomplete.\\n# Click the Docs button to explore the schema.\\n\\nquery {\\n  # Your query here\\n}\\n',
            })
        );
    </script>
</body>
</html>
        """.trimIndent()

        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(graphiqlHtml)
        }

        logger.lifecycle("GraphiQL HTML generated successfully at: ${outputFile.get().asFile}")
    }
}

// Ensure GraphiQL is generated before processing resources
tasks.named("processResources") {
    dependsOn(generateGraphiQL)
}

// Clean up generated GraphiQL
tasks.named("clean") {
    doLast {
        delete(layout.buildDirectory.dir("resources/main/graphiql"))
    }
}
