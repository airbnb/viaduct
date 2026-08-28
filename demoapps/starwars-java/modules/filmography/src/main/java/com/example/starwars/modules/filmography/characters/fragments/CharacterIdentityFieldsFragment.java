package com.example.starwars.modules.filmography.characters.fragments;

import viaduct.java.api.annotations.GraphQLFragment;
import viaduct.java.api.documents.FragmentFromAnnotation;
import viaduct.java.grts.Character;

/** Reusable identity fields for Java resolvers and operations. */
@GraphQLFragment("fragment CharacterIdentityFields on Character { name birthYear }")
public final class CharacterIdentityFieldsFragment extends FragmentFromAnnotation<Character> {}
