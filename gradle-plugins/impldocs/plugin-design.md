# Viaduct plugins and the build process: a shared conceptual model

## Plugin overview

Viaduct’s Gradle plugins organize an application’s schema, tenant modules, and generated code into a build:

- The **settings plugin**, applied in `settings.gradle.kts`, [records which project owns the application](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/settings/src/main/kotlin/viaduct/gradle/ViaductSettingsPlugin.kt#L35-L60) and which projects are its tenant modules. The service engineer supplies the application’s package prefix and each module’s suffix; together these determine a module’s full tenant package name.
- The **application plugin**, applied to the application project, assembles and validates the central schema, generates shared types, and wires tenant modules and their resources into the application.
- The **[Kotlin](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/module/src/main/kotlin/viaduct/gradle/ViaductModulePlugin.kt#L23-L55) and [Java](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/module-java/src/main/kotlin/viaduct/gradle/ViaductJavaModulePlugin.kt#L20-L51) module plugins**, applied to tenant module projects, generate resolver bases, connect shared types to compilation, and produce module manifests describing resolvers and other bootstrap information.
- The **metamodule plugin** provides the common infrastructure that the Kotlin and Java module plugins apply implicitly. It [derives module layout](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/common/src/main/kotlin/viaduct/gradle/ViaductModuleSupport.kt#L31-L61) from the settings declarations, checks module relationships, exposes schema inputs to the application, and provides modules with access to the central schema.

## Three build stages

A Viaduct application build consists of three logical stages: **central-schema**, **module**, and **application assembly**. These stages are based on dependencies between artifacts. Code links refer to [Viaduct v3.0.0](https://github.com/airbnb/viaduct/tree/v3.0.0).

```text
Inputs: module schema partitions, application schema,
        Viaduct built-in schema
                        |
                        v
+------------------------------------------------+
| CENTRAL-SCHEMA STAGE                           |
| Validated schema directory and GRT JARs        |
+------------------------------------------------+
            |                              |
            | schema and GRTs              | GRTs and schema
            v                              | resources
+------------------------------------+     |
| MODULE STAGE                       |     |
| Compiled code and module manifests |     |
+------------------------------------+     |
            |                              |
            | module JARs                  |
            | containing manifests         |
            v                              v
+------------------------------------------------+
| APPLICATION ASSEMBLY STAGE                     |
| Module manifest index and service assembly     |
+------------------------------------------------+
```

Gradle represents these dependencies through **configurations** and **task wiring**. A producer exposes an artifact through a **consumable configuration**. A consumer declares a project dependency and obtains artifacts through a **resolvable configuration**. Attributes distinguish outgoing variants such as schema partitions and the central schema. Task-output providers connect generated artifacts to consuming task inputs and source sets, carrying the dependencies on their producing tasks.

### 1. Central-schema stage

**Artifacts and work.** This stage combines module schema partitions, application-owned schema, and Viaduct built-in schema contributions, then [validates the resulting schema](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/task/AssembleCentralSchemaTask.kt#L103-L153). Third-party plugins can also supply SDL through the [schema-contribution API](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/metamodule/src/main/kotlin/viaduct/gradle/ViaductSchemaContributions.kt#L9-L19); these files are separate from Viaduct’s built-in definitions.

The central schema is represented as a directory of schema files that preserves partition structure and source provenance. Downstream tooling uses this information to identify tenant ownership. This stage includes shared schema-derived generation: the application plugin generates GraphQL Runtime Types (GRTs) and packages them into Kotlin and Java GRT JARs. The [Kotlin GRT JAR also carries the application schema resources](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/ViaductApplicationPlugin.kt#L328-L347) and a [schema resource index](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/task/GenerateSchemaResourceIndexTask.kt#L25-L51) listing their paths, one per line. The [Java GRT JAR contains only classes](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/ViaductApplicationPlugin.kt#L391-L398).

**Gradle wiring.** The application plugin adds the module projects declared in the settings topology [to the `viaductModules` dependency bucket](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/ViaductApplicationPlugin.kt#L126-L135). The metamodule plugin exposes each module’s [schema partition](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/common/src/main/kotlin/viaduct/gradle/ViaductModuleSupport.kt#L127-L140) and [plugin-supplied SDL](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/metamodule/src/main/kotlin/viaduct/gradle/ViaductMetamodulePlugin.kt#L39-L54) through consumable configurations. The application’s resolvable configurations [inherit the module dependencies and request the appropriate artifact variants](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/ViaductApplicationPlugin.kt#L186-L198), which become [inputs to central-schema assembly](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/ViaductApplicationPlugin.kt#L258-L289). Resolving these schema inputs requires their preparation tasks, without requiring module compilation.

The application exposes the schema directory through [the `viaductCentralSchema` consumable configuration](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/ViaductApplicationPlugin.kt#L403-L415) and the GRT JARs through [language-specific consumable configurations](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/ViaductApplicationPlugin.kt#L418-L438). [GRT generation takes the schema-assembly task’s output as its input](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/ViaductApplicationPlugin.kt#L320-L325), establishing their execution order.

### 2. Module stage

**Artifacts and work.** This stage builds each tenant module against the central schema and GRTs. The Kotlin and Java module plugins generate resolver bases and extract resolver descriptors through [Kotlin Symbol Processing (KSP)](https://github.com/airbnb/viaduct/blob/v3.0.0/impldocs/execution-registry-ksp-pipeline.md) or Java annotation processing.

The descriptors are assembled into a [**module manifest**](https://github.com/airbnb/viaduct/blob/v3.0.0/impldocs/execution-registry-bootstrap.md): a JSON resource under `META-INF/viaduct/modules/` describing the module’s resolvers and bootstrap information. [Manifest assembly uses the central schema](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/common/src/main/kotlin/viaduct/gradle/task/AssembleTenantModuleConfigFileTask.kt#L75-L81) to validate resolver selections, including required selection sets (RSS), and determine tenant-local field ownership. Compiled code and the manifest are packaged in the module JAR.

**Gradle wiring.** The metamodule plugin [adds a project dependency](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/common/src/main/kotlin/viaduct/gradle/ViaductModuleSupport.kt#L176-L183) on the owning application to the `viaductApplication` bucket. It accesses the central-schema variant through the resolvable configuration [`viaductCentralSchemaIn`](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/common/src/main/kotlin/viaduct/gradle/ViaductModuleSupport.kt#L143-L155), which inherits that declaration with `extendsFrom(viaductApplication)` and requests `viaduct.kind=central-schema`. Resolver-base generation and manifest assembly consume these schema files. The [Java](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/module-java/src/main/kotlin/viaduct/gradle/ViaductJavaModulePlugin.kt#L64-L86) and [Kotlin](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/module/src/main/kotlin/viaduct/gradle/ViaductModulePlugin.kt#L68-L91) module plugins select the application’s language-specific GRT configuration and connect it to `implementation` ([Java](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/module-java/src/main/kotlin/viaduct/gradle/ViaductJavaModulePlugin.kt#L37-L43); [Kotlin](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/module/src/main/kotlin/viaduct/gradle/ViaductModulePlugin.kt#L41-L47)), making generated types available during compilation.

Generated resolver-base directories are added to the module’s source set ([Kotlin](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/module/src/main/kotlin/viaduct/gradle/ViaductModulePlugin.kt#L50-L55); [Java](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/module-java/src/main/kotlin/viaduct/gradle/ViaductJavaModulePlugin.kt#L46-L51)). The manifest output directory is added to its resources source set ([Kotlin](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/module/src/main/kotlin/viaduct/gradle/ViaductModulePlugin.kt#L138-L165); [Java](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/module-java/src/main/kotlin/viaduct/gradle/ViaductJavaModulePlugin.kt#L108-L142)). Together with explicit dependencies on descriptor extraction, this wiring makes ordinary compilation and resource processing run the required generators and include the manifest in the JAR.

### 3. Application assembly stage

**Artifacts and work.** This stage brings together the compiled modules and their manifests with the shared schema-derived artifacts. The application plugin [generates an **application-wide module manifest index**](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/task/GenerateExecutionRegistryResourceIndexTask.kt#L25-L46) listing manifest resource paths, and checks for duplicate paths and duplicate `(tenantName, apiName)` identities. The manifests remain separate resources in their module JARs.

**Gradle wiring.** The application’s [`runtimeOnly` configuration extends `viaductModules`](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/ViaductApplicationPlugin.kt#L154-L164), so the declared tenant modules become runtime dependencies automatically. A [separate resolvable configuration selects the module runtime JARs](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/ViaductApplicationPlugin.kt#L200-L214) as inputs to manifest-index generation. [The index task’s output directory is added to the application’s resources source set](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/ViaductApplicationPlugin.kt#L250-L255), so resource processing and JAR packaging include it. [The schema-bearing Kotlin GRT JAR is added to the application’s `api` dependencies](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/ViaductApplicationPlugin.kt#L97-L102).

The Viaduct plugin supplies this aggregation and runtime wiring. The service engineer chooses the hosting framework, entry point, and packaging or distribution plugins that assemble the runnable service from the application and its runtime dependencies.

## Stage boundaries and the development loop

### Task ownership and execution order

The application plugin configures both central-schema work and application assembly. The metamodule plugin prepares schema inputs in module projects before the module stage. A task’s owning project or plugin therefore does not determine its stage.

For an application that also contains tenant code in the same project, the plugins connect [local schema](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/common/src/main/kotlin/viaduct/gradle/ViaductModuleSupport.kt#L163-L173) and [GRT outputs](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/module/src/main/kotlin/viaduct/gradle/ViaductModulePlugin.kt#L72-L79) through task-output providers instead of self-referential project dependencies.

### Schema inputs precede module compilation

Module compilation depends on the central schema and GRTs. Schema-input preparation must therefore run without that compilation. A plugin can derive SDL from original tenant sources, but requiring schema-dependent generated resolver bases or compiled module code to produce that SDL would create a dependency cycle.

### Build reuse and incremental execution

The central-schema stage is in the tenant developer’s inner loop: module generation and validation need the schema, and module compilation needs the GRTs.

The central-schema assembly task [declares its inputs and outputs and is cacheable](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/task/AssembleCentralSchemaTask.kt#L40-L100). Gradle can skip it when its inputs and outputs are unchanged, or restore a matching result from the build cache. When the task executes, it [assembles and validates the entire schema](https://github.com/airbnb/viaduct/blob/v3.0.0/gradle-plugins/application/src/main/kotlin/viaduct/gradle/task/AssembleCentralSchemaTask.kt#L103-L153), even if only one partition changed.

If schema inputs are unchanged, editing only a resolver implementation does not require central-schema assembly to execute again. If a plugin derives SDL from that source, the edit can rerun its generator; schema assembly becomes out-of-date if the generated SDL changes. Changes to the central schema or GRTs can invalidate downstream generation and compilation in several modules.
