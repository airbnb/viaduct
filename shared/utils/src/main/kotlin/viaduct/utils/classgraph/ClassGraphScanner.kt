package viaduct.utils.classgraph

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfoList
import io.github.classgraph.ScanResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import viaduct.utils.slf4j.logger

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
        private val singletonInstance = AtomicReference<ClassGraphScanner?>(null)

        // TODO: make these configurable during the initialization or via arguments.
        // TODO: do not expose airbnb internals to OSS repo.
        private val DEFAULT_PACKAGE_PREFIXES = setOf("com.airbnb.viaduct", "viaduct.tenant", "viaduct.engine", "viaduct.api")

        @Volatile
        private var initializedPrefixes: Set<String> = DEFAULT_PACKAGE_PREFIXES

        /**
         * Initialize the singleton instance with specific package prefixes.
         * This should be called early in application startup.
         * Subsequent calls will be ignored.
         */
        fun initialize(packagePrefixes: Set<String>) {
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

        private val log by logger()
        private val scanResultCache =
            Caffeine.newBuilder()
                // ensure on removal/eviction, the ScanResult is closed. This is important so that we don't keep around
                // a reference to the extra classloader that was used to load the classes.
                .removalListener { _: Collection<String>?, value: ScanResult?, _ -> value?.close() }
                .evictionListener { _: Collection<String>?, value: ScanResult?, _ -> value?.close() }
                // it's not necessary to keep these around forever. 10m should be sufficient to allow for
                // server startup + warmup which is where this is primarily used.
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build<Collection<String>, ScanResult>()
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
                            .filter { packagesFilter.any { pkg -> it.packageName.startsWith(pkg) } }
                            .loadClasses()
                    } else {
                        subClassInfos.loadClasses()
                    }
                val implementors =
                    if (!packagesFilter.isEmpty()) {
                        implementorInfos
                            .filter { packagesFilter.any { pkg -> it.packageName.startsWith(pkg) } }
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
                        infos.filter { packagesFilter.any { pkg -> it.packageName.startsWith(pkg) } }
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
                    ClassGraph()
                        .enableClassInfo()
                        .enableAnnotationInfo()
                        .ignoreClassVisibility()
                        .acceptPackages(*packagePrefixes.toTypedArray())
                        .scan()
                }
            log.debug(
                "Scanned '{}' package in {}",
                packagePrefixes,
                elapsedTime
            )
            scanResult
        } ?: throw IllegalStateException("Invariant: scanResult cannot be null.")
}
