package viaduct.serve

import viaduct.service.wiring.graphiql.graphiQLHtml as serviceWiringGraphiQLHtml

/**
 * Returns the HTML for the GraphiQL IDE.
 *
 * Delegates to the GraphiQL resources provided by the service-wiring module.
 * The HTML includes Viaduct customizations:
 * - global-id-plugin.jsx: Provides Global ID encode/decode utilities
 *
 * @return The GraphiQL HTML content
 * @throws IllegalStateException if the GraphiQL HTML cannot be found in resources
 */
fun graphiQLHtml(): String = serviceWiringGraphiQLHtml()
