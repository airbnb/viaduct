package com.example.starwars.modules.filmography.characters.fragments;

import viaduct.java.api.annotations.GraphQLFragment;
import viaduct.java.api.documents.FragmentFromAnnotation;
import viaduct.java.grts.Character;

/** Reusable appearance fields for Java resolvers and operations. */
@GraphQLFragment("fragment CharacterAppearanceFields on Character { eyeColor hairColor }")
public final class CharacterAppearanceFieldsFragment extends FragmentFromAnnotation<Character> {}
