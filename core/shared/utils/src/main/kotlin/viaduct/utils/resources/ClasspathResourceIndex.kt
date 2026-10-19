package viaduct.utils.resources

import java.net.URL
import java.util.Collections

fun ClassLoader.requireResourceUrl(path: String): URL {
    val urls = Collections.list(getResources(path))
    require(urls.size == 1) {
        "Expected exactly one resource '$path', found ${urls.size}: " +
            urls.sortedBy(URL::toExternalForm)
    }
    return urls.single()
}

class ClasspathResourceIndex private constructor(
    val paths: List<String>,
    private val classLoader: ClassLoader,
) {
    fun resourceUrl(path: String): URL {
        require(paths.binarySearch(path) >= 0) { "Resource '$path' is not present in this index" }
        return classLoader.requireResourceUrl(path)
    }

    companion object {
        fun load(
            indexResourcePath: String,
            classLoader: ClassLoader,
        ): ClasspathResourceIndex? {
            val indexUrls = classLoader.resourceUrls(indexResourcePath)
            if (indexUrls.isEmpty()) return null
            require(indexUrls.size == 1) {
                "Expected exactly one resource index '$indexResourcePath', found ${indexUrls.size}: " +
                    indexUrls.sortedBy(URL::toExternalForm)
            }

            val paths = indexUrls.single().openStream().bufferedReader().use { it.readLines() }
            require(paths.all(String::isNotBlank)) {
                "Resource index '$indexResourcePath' from '${indexUrls.single()}' must not contain blank entries"
            }
            require(paths == paths.sorted()) {
                "Resource index '$indexResourcePath' must be sorted"
            }
            val duplicatePaths = paths.groupBy { it }.filterValues { it.size > 1 }.keys
            require(duplicatePaths.isEmpty()) {
                "Resource index '$indexResourcePath' contains duplicate entries: ${duplicatePaths.sorted().joinToString()}"
            }
            val invalidPaths = paths.filter { path ->
                path.startsWith('/') || path.endsWith('/') || path.split('/').any { it == "." || it == ".." }
            }
            require(invalidPaths.isEmpty()) {
                "Resource index '$indexResourcePath' contains invalid resource paths: ${invalidPaths.sorted().joinToString()}"
            }
            return ClasspathResourceIndex(paths, classLoader)
        }

        private fun ClassLoader.resourceUrls(path: String): List<URL> = Collections.list(getResources(path))
    }
}
