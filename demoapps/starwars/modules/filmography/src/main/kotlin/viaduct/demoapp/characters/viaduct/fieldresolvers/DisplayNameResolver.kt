package viaduct.demoapp.characters.viaduct.fieldresolvers

import viaduct.api.Resolver
import viaduct.demoapp.filmography.resolverbases.CharacterResolvers

/**
 * Shorthand fragment syntax example - delegates to the name field
 *
 * @resolver("name"): Shorthand fragment syntax that delegates resolution to another field.
 *                   This resolver will automatically fetch the "name" field and return its value.
 */
// tag::resolver_example[8] Example of a simple resolver
@Resolver("name")
class DisplayNameResolver : CharacterResolvers.DisplayName() {
    override suspend fun resolve(ctx: Context): String? {
        // Directly returns the name of the character from the context. The "name" field is
        // automatically fetched due to the @Resolver annotation.
        return ctx.objectValue.getName()
    }
}
