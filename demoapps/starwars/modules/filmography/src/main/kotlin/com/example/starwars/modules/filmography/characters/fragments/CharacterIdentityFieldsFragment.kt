@file:OptIn(ExperimentalApi::class)

package com.example.starwars.modules.filmography.characters.fragments

import viaduct.api.documents.FragmentFromAnnotation
import viaduct.api.documents.GraphQLFragment
import viaduct.api.grts.Character
import viaduct.apiannotations.ExperimentalApi

/**
 * Named fragment example.
 *
 * `@GraphQLFragment` declares a reusable GraphQL fragment on a Kotlin singleton `object`. Once
 * declared, the fragment can be spread (`...CharacterIdentityFields`) from the `objectValueFragment`
 * or `queryValueFragment` of any resolver in the same tenant module, instead of repeating the same
 * selections in each resolver.
 *
 * Here, both `CharacterDisplaySummaryResolver` and `CharacterRichSummaryResolver` need a
 * Character's `name` and `birthYear`, so they share this fragment.
 */
// tag::named_fragment_example[2] Declaring a named fragment with @GraphQLFragment
@GraphQLFragment("fragment CharacterIdentityFields on Character { name birthYear }")
object CharacterIdentityFieldsFragment : FragmentFromAnnotation<Character>()
