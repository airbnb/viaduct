@file:OptIn(ExperimentalApi::class)

package com.example.starwars.modules.filmography.characters.fragments

import viaduct.api.documents.FragmentFromAnnotation
import viaduct.api.documents.GraphQLFragment
import viaduct.api.grts.Character
import viaduct.apiannotations.ExperimentalApi

/**
 * A second named fragment, used to show that a resolver can spread more than one fragment.
 *
 * `CharacterFormattedDescriptionResolver` needs a Character's identity fields (`name`, `birthYear`)
 * and its appearance fields (`eyeColor`, `hairColor`). It composes [CharacterIdentityFieldsFragment]
 * with this fragment by spreading both in one selection set.
 */
// tag::appearance_fragment_example[2] A second named fragment for appearance fields
@GraphQLFragment("fragment CharacterAppearanceFields on Character { eyeColor hairColor }")
object CharacterAppearanceFieldsFragment : FragmentFromAnnotation<Character>()
