package viaduct.graphiql

import java.io.File
import java.net.URL

/**
 * Customizes the official GraphiQL HTML for use with Viaduct.
 *
 * Downloads the base HTML from a specific GraphiQL release and applies Viaduct customizations
 * by parsing the HTML structure and inserting our code at appropriate locations.
 * This is more robust than text-based patches as it adapts to HTML structure changes.
 */
class GraphiQLHtmlCustomizer(
    private val sourceUrl: String,
    private val outputFile: File
) {
    /**
     * Downloads and customizes the GraphiQL HTML, writing the result to the output file.
     */
    fun customize() {
        val html = downloadHtml()
        val customized = applyCustomizations(html)
        writeOutput(customized)
    }

    private fun downloadHtml(): String {
        val tempFile = File.createTempFile("graphiql", ".html")
        try {
            URL(sourceUrl).openStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return tempFile.readText()
        } finally {
            tempFile.delete()
        }
    }

    private fun applyCustomizations(html: String): String {
        var customized = html

        // 1. Add Viaduct disclaimer after the original copyright comment
        val disclaimer = """
<!--
 *  VIADUCT CUSTOMIZATIONS:
 *  This file is generated from the official GraphiQL CDN example and customized for Viaduct.
 *  DO NOT EDIT MANUALLY - regenerate using: ./gradlew :core:service:service-wiring:generateGraphiQLHtml
 *
 *  Customizations applied:
 *  - Changed title to "GraphiQL - Viaduct"
 *  - Changed endpoint from demo URL to "/graphql"
 *  - Added introspection-patch.js to fix GraphQL Java compatibility with GraphiQL 5
 *    (GraphQL Java omits empty arrays that GraphiQL 5 strictly requires)
 *  - Added global-id-plugin.jsx for encoding/decoding Viaduct Global IDs
 *  - Modified initialization to load plugins asynchronously
 *  - Added custom default query with usage instructions
-->"""
        customized = customized.replaceFirst(
            Regex("""(-->)\s*(\n<!doctype)""", RegexOption.IGNORE_CASE),
            "$1$disclaimer$2"
        )

        // 2. Update title
        customized = customized.replace(
            Regex("<title>.*?</title>"),
            "<title>GraphiQL - Viaduct</title>"
        )

        // 3. Change demo endpoint to /graphql
        customized = customized.replace(
            "url: 'https://countries.trevorblades.com'",
            "url: '/graphql'"
        )

        // 4. Find the module script and inject our customizations
        val moduleScriptPattern = Regex(
            """(<script type="module">)(.*?)(</script>)""",
            RegexOption.DOT_MATCHES_ALL
        )

        val moduleScriptMatch = moduleScriptPattern.find(customized)
        if (moduleScriptMatch != null) {
            val (opening, scriptContent, closing) = moduleScriptMatch.destructured

            // Add our imports at the beginning of the module script
            val viaductImports = """
      import { loadJSX } from '/js/jsx-loader.js';
      import { createPatchedFetcher } from '/js/introspection-patch.js';
"""

            // Wrap the fetcher creation with our patch
            var modifiedScript = scriptContent.replace(
                Regex("""const fetcher = createGraphiQLFetcher\(\{[\s\S]*?\}\);"""),
                """const baseFetcher = createGraphiQLFetcher({
        url: '/graphql',
      });
      const fetcher = createPatchedFetcher(baseFetcher);"""
            )

            // Modify the plugin initialization to load our Global ID plugin
            modifiedScript = modifiedScript.replace(
                "const plugins = [HISTORY_PLUGIN, explorerPlugin()];",
                """// Load Viaduct plugins asynchronously
      async function loadPlugins() {
        try {
          const pluginModule = await loadJSX('/js/global-id-plugin.jsx');
          const createGlobalIdPlugin = pluginModule.createGlobalIdPlugin;
          const globalIdPlugin = createGlobalIdPlugin(React);
          return [HISTORY_PLUGIN, explorerPlugin(), globalIdPlugin];
        } catch (error) {
          console.error('Failed to load Viaduct Global ID plugin:', error);
          return [HISTORY_PLUGIN, explorerPlugin()];
        }
      }"""
            )

            // Replace the App rendering to be async and use our plugins
            modifiedScript = modifiedScript.replace(
                Regex("""function App\(\)[\s\S]*?root\.render\(React\.createElement\(App\)\);"""),
                """async function initGraphiQL() {
        const plugins = await loadPlugins();
        const explorer = plugins.find(p => p.title === 'Explorer');
        const defaultQuery = `# Welcome to Viaduct GraphiQL!
#
# Start typing your GraphQL query here.
# Press Ctrl+Space for autocomplete.
# Click the Docs button to explore the schema.
# Use the Global ID Utils plugin (key icon) to encode/decode Viaduct Global IDs.

query {
  # Your query here
}
`;

        function App() {
          return React.createElement(GraphiQL, {
            fetcher,
            plugins,
            visiblePlugin: explorer,
            defaultQuery,
            defaultEditorToolsVisibility: true,
          });
        }

        const container = document.getElementById('graphiql');
        const root = ReactDOM.createRoot(container);
        root.render(React.createElement(App));
      }

      initGraphiQL();"""
            )

            // Reconstruct the script with our imports
            val newModuleScript = opening + viaductImports + modifiedScript + closing
            customized = customized.replace(moduleScriptMatch.value, newModuleScript)
        } else {
            throw IllegalStateException(
                "Could not find module script in GraphiQL HTML. HTML structure may have changed."
            )
        }

        return customized
    }

    private fun writeOutput(html: String) {
        outputFile.parentFile.mkdirs()
        outputFile.writeText(html)
    }
}
