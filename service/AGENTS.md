In the Viaduct system design we distinguish _service engineers_ who are the people who integrate Viaduct into an organizations tech stack (eg, web-serving, observability, and security infrastructure) from _application developers_ (aka, _tenant developers_) who write application code on top of Viaduct.  The facilities in this `service` subdirectory are intended to be used by service engineers.

This directory contains three subprojects: `service/api` which defines the interfaces needed to integrate Viaduct into a tech stack, `service/runtime` which contains the implementation of those interfaces.  The api project literally contains (mostly) unimplemented Kotlin interfaces whose implementations are found in the runtime project, which provides us with strong encapsulation of implementation details.  The `service/wiring` project contains the factories that instantiate classes from the runtime project.  In particular, [`viaduct.service.BasicViaductFactory`](wiring/src/main/kotlin/viaduct/service/BasicViaductFactory.kt) (a simpler API) and 
 [`viaduct.service.ViaductBuilder`](wiring/src/main/kotlin/viaduct/service/ViaductBuilder.kt) (a fuller-featured API) create instances of [`viaduct.service.api.Viaduct`](../api/src/main/kotlin/viaduct/service/api/Viaduct.kt), the Viaduct service object.

To support integration with infrastructure such as observability, Viaduct takes a Service Provider Interface (SPI) approach.  In particular the `api/src/main/kotlin/viaduct/service/api/spi` directory contains interfaces intended to be implemented by Service Engineers.  For example, `viaduct.service.api.spi.ErrorReporter` is an interface for integrating GraphQL into an organization's error-logging infrastructure.

## Module Descriptions

- [`module.md`](module.md) — Package-level descriptions for the Service API and Service Wiring packages.


