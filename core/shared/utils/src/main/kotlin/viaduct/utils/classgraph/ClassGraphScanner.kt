package viaduct.utils.classgraph

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.benmanes.caffeine.cache.Scheduler
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfoList
import io.github.classgraph.ScanResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import viaduct.utils.slf4j.logger

/**
 * Finds classpath resource paths matching [regex] under [resourcePathPrefix].
 *
 * [resourcePathPrefix] is normalized to ClassGraph's resource path format. Package-like values such as
 * `com.example.foo` become `com/example/foo`, while path-like values such as `graphql/` keep path
 * separators and have leading/trailing slashes removed.
 */
fun findResourcePathsMatching(
    resourcePathPrefix: String,
    regex: Regex
): List<String> =
    ClassGraph()
        .acceptPaths(normalizeResourcePathPrefix(resourcePathPrefix))
        .scan()
        .use { scanResult ->
            scanResult
                .getResourcesMatchingPattern(regex.toPattern())
                .paths
                .distinct()
        }

private fun normalizeResourcePathPrefix(resourcePathPrefix: String): String {
    require(resourcePathPrefix.isNotBlank()) { "resourcePathPrefix must not be blank" }

    val trimmedPrefix = resourcePathPrefix.trim('/')
    return if (trimmedPrefix.contains('/')) {
        trimmedPrefix
    } else {
        trimmedPrefix.replace('.', '/')
    }
}

/**
 * Core class graph scanning functionality for Viaduct.
 *
 * This class provides efficient classpath scanning with caching support.
 * Use the singleton [INSTANCE] for shared scanning across the application,
 * or create custom instances for specific package prefixes.
 */
@OptIn(ExperimentalTime::class)
class ClassGraphScanner(private val packagePrefixes: Collection<String>) {
    companion object {
        private val AIRBNB_ONLY_EXTRA_CLASSPATH = "/srv/classes"
        private val singletonInstance = AtomicReference<ClassGraphScanner?>(null)

        // TODO: make these configurable during the initialization or via arguments.
        // TODO: do not expose airbnb internals to OSS repo.
        private val DEFAULT_PACKAGE_PREFIXES = setOf("com.airbnb.viaduct", "viaduct.tenant", "viaduct.engine", "viaduct.api")

        @Volatile
        private var initializedPrefixes: Set<String> = DEFAULT_PACKAGE_PREFIXES

        @Volatile
        private var configuredCacheExpiration: Duration? = 10.minutes

        /**
         * Initialize the singleton instance with specific package prefixes and environment context.
         * This should be called early in application startup, before the first scan is performed.
         * Subsequent calls will be ignored.
         *
         * @param packagePrefixes the package prefixes to scan. Defaults to [DEFAULT_PACKAGE_PREFIXES].
         * @param neverExpiresCache if true, scan results never expire so that hotswap always hits the
         * cached result. If false, results expire after 10 minutes. Defaults to false.
         */
        fun initialize(
            packagePrefixes: Set<String> = DEFAULT_PACKAGE_PREFIXES,
            neverExpiresCache: Boolean = false,
        ) {
            configuredCacheExpiration = if (neverExpiresCache) null else 10.minutes
            if (singletonInstance.compareAndSet(null, forPackagePrefixes(packagePrefixes))) {
                initializedPrefixes = packagePrefixes
            }
        }

        /**
         * The singleton instance of ClassGraphScanner.
         * If not explicitly initialized via [initialize], will use default package prefixes.
         */
        val INSTANCE: ClassGraphScanner
            get() {
                // Fast path: already initialized
                singletonInstance.get()?.let { return it }

                // Slow path: create default and try to set it
                val default = forPackagePrefixes(DEFAULT_PACKAGE_PREFIXES)
                singletonInstance.compareAndSet(null, default)

                // Return whatever is there (might be ours or another thread's)
                return singletonInstance.get()!!
            }

        /**
         * Create a new scanner for a single package prefix.
         */
        fun forPackagePrefix(packagePrefix: String) = forPackagePrefixes(setOf(packagePrefix))

        /**
         * Create a new scanner for multiple package prefixes.
         */
        fun forPackagePrefixes(packagePrefixes: Collection<String>) = ClassGraphScanner(packagePrefixes)

        /**
         * Get an optimized scanner for a single package prefix.
         *
         * If the given package prefix falls within the initialized scanned packages,
         * returns the shared [INSTANCE] to avoid redundant scanning.
         * Otherwise, creates a new scanner for the specific package.
         *
         * @param packagePrefix The package prefix to scan
         * @return A ClassGraphScanner that covers the given package prefix
         */
        fun optimizedForPackagePrefix(packagePrefix: String): ClassGraphScanner =
            if (initializedPrefixes.any { packagePrefix.startsWith(it) }) {
                INSTANCE
            } else {
                forPackagePrefix(packagePrefix)
            }

        /**
         * Invalidate the scanner's cache for a specific package prefix.
         *
         * @param packagePrefix the packagePrefix to invalidate cache for
         */
        fun invalidateCache(packagePrefix: String) {
            // The cache key is Collection<String>, so we need to invalidate the correct key
            val keysToInvalidate = scanResultCache.asMap().keys.filter { keySet ->
                keySet.any { it == packagePrefix || packagePrefix.startsWith("$it.") }
            }
            log.info("ClassGraphScanner keysToInvalidate: {}", keysToInvalidate)
            keysToInvalidate.forEach { key ->
                scanResultCache.invalidate(key)
            }
        }

        private val log by logger()
        private val scanResultCache by lazy {
            Caffeine.newBuilder()
                // Close ScanResult only on time-based eviction or size-based eviction,
                // NOT on explicit invalidation. When invalidateCache() removes an entry,
                // it may still be in use by concurrent request threads (e.g., lazy init).
                // Closing a ScanResult that is still referenced causes
                // "Cannot use a ScanResult after it has been closed".
                .removalListener { _: Collection<String>?, value: ScanResult?, cause: RemovalCause? ->
                    if (cause != null && cause != RemovalCause.EXPLICIT) {
                        value?.close()
                    }
                }
                .evictionListener { _: Collection<String>?, value: ScanResult?, _ -> value?.close() }
                .apply {
                    configuredCacheExpiration?.let { expireAfterWrite(it.toLong(DurationUnit.MINUTES), TimeUnit.MINUTES) }
                }
                .scheduler(Scheduler.systemScheduler())
                .build<Collection<String>, ScanResult>()
        }
    }

    /**
     * Get all subtypes (subclasses and implementors) of the given type.
     *
     * @param type The base type to find subtypes of
     * @param packagesFilter Optional filter to restrict results to specific packages
     * @return Set of classes that extend or implement the given type
     */
    fun <T : Any?> getSubTypesOf(
        type: Class<T>,
        packagesFilter: Collection<String> = emptySet()
    ): Set<Class<out T>> {
        val (classes, elapsedTime) =
            measureTimedValue {
                val scanResult = getScanResult()
                val subClassInfos = scanResult.getSubclasses(type.name)
                val implementorInfos =
                    if (type.isInterface) {
                        scanResult.getClassesImplementing(type.name)
                    } else {
                        ClassInfoList.emptyList()
                    }
                val subClasses =
                    if (!packagesFilter.isEmpty()) {
                        subClassInfos
                            .filter { packagesFilter.any { pkg -> it.packageName == pkg || it.packageName.startsWith("$pkg.") } }
                            .loadClasses()
                    } else {
                        subClassInfos.loadClasses()
                    }
                val implementors =
                    if (!packagesFilter.isEmpty()) {
                        implementorInfos
                            .filter { packagesFilter.any { pkg -> it.packageName == pkg || it.packageName.startsWith("$pkg.") } }
                            .loadClasses()
                    } else {
                        implementorInfos.loadClasses()
                    }
                (subClasses + implementors).toSet()
            }
        log.debug(
            "Got {} results for subtypes of {} in {}",
            classes.size,
            type.name,
            elapsedTime
        )
        @Suppress("UNCHECKED_CAST")
        return classes as Set<Class<out T>>
    }

    /**
     * Get all types annotated with the given annotation.
     *
     * @param annotation The annotation to search for
     * @param packagesFilter Optional filter to restrict results to specific packages
     * @return Set of classes annotated with the given annotation
     */
    fun getTypesAnnotatedWith(
        annotation: Class<out Annotation>,
        packagesFilter: Collection<String> = emptySet()
    ): Set<Class<*>> {
        val (classes, elapsedTime) =
            measureTimedValue {
                val scanResult = getScanResult()
                val infos =
                    scanResult
                        .getClassesWithAnnotation(annotation.name)
                val filteredInfos =
                    if (packagesFilter.isNotEmpty()) {
                        infos.filter { packagesFilter.any { pkg -> it.packageName == pkg || it.packageName.startsWith("$pkg.") } }
                    } else {
                        infos
                    }
                filteredInfos.loadClasses().toSet()
            }
        log.debug(
            "Got {} results for types annotated with {} in {}",
            classes.size,
            annotation.name,
            elapsedTime
        )
        return classes
    }

    /**
     * Get the underlying scan result, using cache if available.
     */
    internal fun getScanResult(): ScanResult =
        scanResultCache.get(packagePrefixes) {
            val (scanResult, elapsedTime) =
                measureTimedValue {
                    val classGraph = ClassGraph()
                        .enableClassInfo()
                        .enableAnnotationInfo()
                        .ignoreClassVisibility()
                        .acceptPackages(*packagePrefixes.toTypedArray())

                    // Add extra classpath to the scan so ClassGraph can discover NEW .class files
                    // that were placed there at runtime (e.g., by a development-time class reloader).
                    //
                    // Uses addClassLoader with a URLClassLoader (not overrideClasspath) to preserve
                    // ClassGraph's default classpath discovery (existing classloaders, module path, etc.).
                    //
                    // Note: For classes that exist in BOTH the JAR and the extra class path, ClassGraph
                    // will use the JAR's bytecode (found first). This is acceptable because:
                    // - ClassGraph is used only for class DISCOVERY (getSubTypesOf, getTypesAnnotatedWith)
                    // - Annotation values are read via Java reflection on the JVM-loaded class,
                    //   not from ClassGraph's bytecode-parsed ClassInfo
                    val extraClasspath = java.io.File(AIRBNB_ONLY_EXTRA_CLASSPATH)
                    if (extraClasspath.exists() && extraClasspath.isDirectory) {
                        log.info("Adding extra classpath to ClassGraph scan: {}", extraClasspath.absolutePath)
                        classGraph.addClassLoader(java.net.URLClassLoader(arrayOf(extraClasspath.toURI().toURL())))
                    }

                    classGraph.scan()
                }
            log.info(
                "Scanned '{}' package in {}",
                packagePrefixes,
                elapsedTime
            )
            scanResult
        } ?: throw IllegalStateException("Invariant: scanResult cannot be null.")
}
