# spring-starter (placeholder)

This is a **placeholder** end-to-end demo application for the Viaduct Java API.

Despite the directory name, it does **not** yet use the Spring Framework. The current
entry point (`com.example.viadapp.ViaductApplication`) is a plain Java `main()` that
builds a `Viaduct` instance directly via `ViaductBuilder` and executes a single
GraphQL operation from the command line.

The intent is to evolve this demo into a proper Spring Boot application that shows
how to embed Viaduct's Java API inside a Spring-managed web service. Until then,
treat this app as scaffolding — the package layout, resolver wiring, and Gradle
plugin configuration are in place, but the Spring integration is intentionally
deferred.

For a fully-fledged (non-Spring) Viaduct demo, see
[`demoapps/starwars`](../starwars).
