package viaduct.scoping

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/**
 * The build-to-runtime manifest carrying an application's [SchemaScoping] beside the schema it describes.
 *
 * The build writes one; runtime reads it to learn the declared scope universe instead of inferring a
 * universe from the schema it is filtering. An application that declares no scopes contributes no
 * manifest, and runtime keeps inferring.
 *
 * The wire shape mirrors `scopes.yaml` key for key, so a build tool that reads the YAML can emit the
 * JSON without translating between them, and [parse] applies the same rules to both.
 */
@InternalApi
@OptIn(ExperimentalApi::class)
object SchemaScopingManifest {
    /** Classpath location of the manifest. */
    const val RESOURCE_PATH: String = "META-INF/viaduct/schema-scoping.json"

    /**
     * Neither a repeated key nor content trailing the document reaches [SchemaScopeDefinitions]: the
     * parser resolves one to its last value and drops the other — the same split `ViaductScopesYaml`
     * owns for YAML.
     */
    private val mapper: ObjectMapper =
        ObjectMapper(JsonFactory().apply { enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION) })
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    /** Encodes [scoping] with every list sorted, so the same definitions always produce the same bytes. */
    fun encode(scoping: SchemaScoping): String =
        mapper.writeValueAsString(
            mapOf(
                "schemaScopes" to scoping.scopeUniverse.sorted(),
                "scopedSchemas" to scoping.scopedSchemas.toSortedMap().map { (id, scopes) ->
                    mapOf("id" to id, "scopes" to scopes.sorted())
                },
                "version" to scoping.version,
            ),
        )

    /**
     * Parses one manifest, naming [source] in every failure so a reader can find the offending resource.
     *
     * Decodes to the shape [SchemaScopeDefinitions] takes, so a manifest is held to the rules that
     * accepted the `scopes.yaml` it came from and reports the same error codes. Those messages name
     * `scopes.yaml`, which is where a manifest written by Viaduct's own build has to be corrected. Any
     * change to how the definitions are interpreted requires a new [SchemaScoping.CURRENT_VERSION], so an
     * unrecognized field at a supported version is a producer error rather than a forward-compatible
     * addition.
     *
     * @throws IllegalArgumentException if the content is unreadable, or does not satisfy
     *  [SchemaScopeDefinitions].
     */
    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    fun parse(
        input: InputStream,
        source: String,
    ): SchemaScoping {
        val decoded = try {
            mapper.readValue(input, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            throw IllegalArgumentException("$source could not be read as a schema scoping manifest: ${e.message}", e)
        }
        val scoping = when (val result = SchemaScopeDefinitions.fromDecoded(decoded)) {
            is SchemaScopeDefinitions.Result.Success -> result.scoping
            is SchemaScopeDefinitions.Result.Failure -> throw IllegalArgumentException(
                result.errors.joinToString(
                    prefix = "$source declares invalid schema scope definitions:",
                    separator = "",
                ) { "\n  - [${it.code}] ${it.message}" },
            )
        }
        require(scoping.isScoped) {
            "$source declares no scope universe, so it cannot describe any scoped schema. A manifest is " +
                "written only for an application that declares scopes; remove it, or declare the universe " +
                "under schemaScopes in ${SchemaScopeDefinitions.SOURCE_FILE_NAME}."
        }
        return scoping
    }

    /**
     * Reads the manifest from the context class loader, falling back to this class's loader. Callers that
     * resolve the manifest after startup should capture the loader while the application still owns the
     * thread and use [load] directly.
     */
    fun load(): SchemaScoping? = load(Thread.currentThread().contextClassLoader ?: javaClass.classLoader)

    /**
     * Returns the declared definitions, or `null` when no manifest is present — the state of every
     * application that declares no scopes.
     *
     * @throws IllegalStateException if more than one manifest is on the classpath, which happens when two
     *  applications each contribute one.
     */
    fun load(classLoader: ClassLoader): SchemaScoping? {
        val urls = classLoader.getResources(RESOURCE_PATH).toList()
        if (urls.isEmpty()) return null
        if (urls.size > 1) {
            throw IllegalStateException(
                "Found ${urls.size} $RESOURCE_PATH resources on the classpath, so which scope definitions " +
                    "apply is ambiguous: ${urls.map { it.toString() }}. Exactly one Viaduct application may " +
                    "contribute this resource.",
            )
        }
        val url = urls.single()
        return url.openStream().use { parse(it, url.toString()) }
    }
}
