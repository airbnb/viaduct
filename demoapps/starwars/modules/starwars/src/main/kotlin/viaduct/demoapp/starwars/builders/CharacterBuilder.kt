package viaduct.demoapp.starwars.builders

import viaduct.api.context.ExecutionContext
import viaduct.api.grts.Character
import viaduct.demoapp.starwars.data.StarWarsData

class CharacterBuilder(private val ctx: ExecutionContext) {
    fun build(character: StarWarsData.Character): Character =
        Character.Builder(ctx)
            .id(ctx.globalIDFor(Character.Reflection, character.id))
            .name(character.name)
            .birthYear(character.birthYear)
            .eyeColor(character.eyeColor)
            .gender(character.gender)
            .hairColor(character.hairColor)
            .height(character.height)
            .mass(character.mass?.toDouble())
            .created(character.created.toString())
            .edited(character.edited.toString())
            .build()
}
