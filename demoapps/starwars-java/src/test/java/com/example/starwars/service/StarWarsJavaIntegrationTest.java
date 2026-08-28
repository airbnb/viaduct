package com.example.starwars.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starwars.modules.filmography.characters.models.Character;
import com.example.starwars.modules.filmography.characters.models.CharacterFilmsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
final class StarWarsJavaIntegrationTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject
  @Client("/")
  HttpClient client;

  @Test
  void resolvesFilmographyAndUniverseInOneApplication() throws Exception {
    JsonNode response =
        execute(
            """
            query {
              allCharacters(limit: 1) {
                id
                name
                displaySummary
                filmCount
                homeworld { name }
                species { name }
              }
              allFilms(limit: 1) {
                id
                title
                summary
                characterCountSummary
                characters { name }
                dataSource
              }
              allPlanets(limit: 1) { name }
              allStarships(limit: 1) { name model }
              allVehicles(limit: 1) { name model }
            }
            """,
            Map.of());

    assertNoErrors(response);
    assertThat(response.at("/data/allCharacters/0/name").asText()).isEqualTo("Luke Skywalker");
    assertThat(response.at("/data/allCharacters/0/homeworld/name").asText()).isEqualTo("Tatooine");
    assertThat(response.at("/data/allFilms/0/title").asText()).isEqualTo("A New Hope");
    assertThat(response.at("/data/allFilms/0/characters")).hasSize(5);
    assertThat(response.at("/data/allFilms/0/dataSource").asText())
        .isEqualTo("film-archive-service:1");
    assertThat(response.at("/data/allPlanets/0/name").asText()).isEqualTo("Tatooine");
    assertThat(response.at("/data/allStarships/0/name").asText()).isEqualTo("Millennium Falcon");
    assertThat(response.at("/data/allVehicles/0/name").asText()).isEqualTo("Speeder bike");
  }

  @Test
  void resolvesCharacterAndFilmNodes() throws Exception {
    JsonNode ids =
        execute("query { allCharacters(limit: 1) { id } allFilms(limit: 1) { id } }", Map.of());
    assertNoErrors(ids);
    String characterId = ids.at("/data/allCharacters/0/id").asText();
    String filmId = ids.at("/data/allFilms/0/id").asText();

    JsonNode response =
        execute(
            """
            query {
              character: node(id: "%s") {
                ... on Character { name birthYear eyeColor }
              }
              film: node(id: "%s") {
                ... on Film { title episodeID director }
              }
            }
            """
                .formatted(characterId, filmId),
            Map.of());

    assertNoErrors(response);
    assertThat(response.at("/data/character/name").asText()).isEqualTo("Luke Skywalker");
    assertThat(response.at("/data/film/title").asText()).isEqualTo("A New Hope");
    assertThat(response.at("/data/film/episodeID").asInt()).isEqualTo(4);
  }

  @Test
  void resolvesEveryUniverseNodeTypeAndHttpVariables() throws Exception {
    JsonNode ids =
        execute(
            """
            query {
              allPlanets(limit: 1) { id }
              allSpecies(limit: 1) { id }
              allStarships(limit: 1) { id }
              allVehicles(limit: 1) { id }
            }
            """,
            Map.of());
    assertNoErrors(ids);

    JsonNode response =
        execute(
            """
            query Nodes($planet: ID!, $species: ID!, $starship: ID!, $vehicle: ID!) {
              planet: node(id: $planet) {
                ... on Planet { name dataSource }
              }
              species: node(id: $species) {
                ... on Species { name homeworld { name } rarityLevel }
              }
              starship: node(id: $starship) {
                ... on Starship { name model }
              }
              vehicle: node(id: $vehicle) {
                ... on Vehicle { name model }
              }
            }
            """,
            Map.of(
                "planet", ids.at("/data/allPlanets/0/id").asText(),
                "species", ids.at("/data/allSpecies/0/id").asText(),
                "starship", ids.at("/data/allStarships/0/id").asText(),
                "vehicle", ids.at("/data/allVehicles/0/id").asText()),
            Map.of("X-Viaduct-Scopes", "extras"));

    assertNoErrors(response);
    assertThat(response.at("/data/planet/name").asText()).isEqualTo("Tatooine");
    assertThat(response.at("/data/planet/dataSource").asText()).isEqualTo("universe-catalog-db:1");
    assertThat(response.at("/data/species/name").asText()).isEqualTo("Human");
    assertThat(response.at("/data/species/homeworld/name").asText()).isEqualTo("Earth");
    assertThat(response.at("/data/species/rarityLevel").asText()).isEqualTo("Common");
    assertThat(response.at("/data/starship/name").asText()).isEqualTo("Millennium Falcon");
    assertThat(response.at("/data/vehicle/name").asText()).isEqualTo("Speeder bike");
  }

  @Test
  void supportsEveryConnectionPaginationMode() throws Exception {
    JsonNode firstPages =
        execute(
            """
            query {
              characters: allCharactersConnection(first: 2) {
                edges { cursor node { name } }
                pageInfo { hasNextPage hasPreviousPage endCursor }
              }
              films: allFilmsConnection(first: 2) {
                edges { node { title } }
                pageInfo { hasNextPage }
                totalCount
              }
              planets: allPlanetsConnection(last: 2) {
                edges { cursor node { name } }
                pageInfo { hasNextPage hasPreviousPage startCursor }
              }
              starships: allStarshipsConnection(first: 1) {
                edges { cursor node { name } }
                pageInfo { hasNextPage hasPreviousPage endCursor }
              }
              speciesFirst: allSpeciesConnection(first: 1) {
                edges { cursor node { name } }
                pageInfo { hasNextPage hasPreviousPage }
              }
              speciesLast: allSpeciesConnection(last: 1) {
                edges { cursor node { name } }
                pageInfo { hasNextPage hasPreviousPage startCursor }
              }
            }
            """,
            Map.of());

    assertNoErrors(firstPages);
    assertThat(firstPages.at("/data/characters/edges")).hasSize(2);
    assertThat(firstPages.at("/data/characters/pageInfo/hasNextPage").asBoolean()).isTrue();
    assertThat(firstPages.at("/data/films/totalCount").asInt()).isEqualTo(3);
    assertThat(firstPages.at("/data/films/pageInfo/hasNextPage").asBoolean()).isTrue();
    assertThat(firstPages.at("/data/planets/edges")).hasSize(2);
    assertThat(firstPages.at("/data/planets/pageInfo/hasPreviousPage").asBoolean()).isTrue();
    assertThat(firstPages.at("/data/starships/edges/0/node/name").asText())
        .isEqualTo("Millennium Falcon");
    assertThat(firstPages.at("/data/starships/pageInfo/hasNextPage").asBoolean()).isTrue();
    assertThat(firstPages.at("/data/speciesFirst/edges/0/node/name").asText()).isEqualTo("Human");
    assertThat(firstPages.at("/data/speciesLast/edges/0/node/name").asText()).isEqualTo("Wookiee");
    assertThat(firstPages.at("/data/speciesLast/pageInfo/hasPreviousPage").asBoolean()).isTrue();

    JsonNode adjacentPages =
        execute(
            """
            query Pages(
              $charactersAfter: String!
              $planetsBefore: String!
              $starshipsAfter: String!
              $speciesBefore: String!
            ) {
              characters: allCharactersConnection(first: 2, after: $charactersAfter) {
                edges { node { name } }
                pageInfo { hasPreviousPage }
              }
              planets: allPlanetsConnection(last: 2, before: $planetsBefore) {
                edges { node { name } }
              }
              starships: allStarshipsConnection(first: 1, after: $starshipsAfter) {
                edges { node { name } }
                pageInfo { hasPreviousPage }
              }
              species: allSpeciesConnection(last: 1, before: $speciesBefore) {
                edges { node { name } }
              }
            }
            """,
            Map.of(
                "charactersAfter", firstPages.at("/data/characters/pageInfo/endCursor").asText(),
                "planetsBefore", firstPages.at("/data/planets/pageInfo/startCursor").asText(),
                "starshipsAfter", firstPages.at("/data/starships/pageInfo/endCursor").asText(),
                "speciesBefore", firstPages.at("/data/speciesLast/pageInfo/startCursor").asText()),
            Map.of());

    assertNoErrors(adjacentPages);
    assertThat(adjacentPages.at("/data/characters/edges/0/node/name").asText())
        .isEqualTo("Han Solo");
    assertThat(adjacentPages.at("/data/characters/pageInfo/hasPreviousPage").asBoolean()).isTrue();
    assertThat(adjacentPages.at("/data/planets/edges/0/node/name").asText()).isEqualTo("Corellia");
    assertThat(adjacentPages.at("/data/starships/edges/0/node/name").asText()).isEqualTo("X-wing");
    assertThat(adjacentPages.at("/data/starships/pageInfo/hasPreviousPage").asBoolean()).isTrue();
    assertThat(adjacentPages.at("/data/species/edges/0/node/name").asText()).isEqualTo("Human");
  }

  @Test
  void resolvesConditionalSelectionsAndNamedFragments() throws Exception {
    JsonNode response =
        execute(
            """
            query {
              allCharacters(limit: 1) {
                basicProfile: characterProfile(includeDetails: false)
                detailedProfile: characterProfile(includeDetails: true)
                appearanceDescription
                formattedDescription(format: "detailed")
                characterStats(minAge: 18, maxAge: 80)
                richSummary
              }
            }
            """,
            Map.of());

    assertNoErrors(response);
    JsonNode character = response.at("/data/allCharacters/0");
    assertThat(character.path("basicProfile").asText())
        .isEqualTo("Character Profile: Luke Skywalker (basic info only)");
    assertThat(character.path("detailedProfile").asText())
        .contains("Born: 19BBY", "Height: 172cm", "Mass: 77.0kg");
    assertThat(character.path("appearanceDescription").asText())
        .isEqualTo("Luke Skywalker has blue eyes and blond hair");
    assertThat(character.path("formattedDescription").asText())
        .isEqualTo("Luke Skywalker (born 19BBY) - blue eyes, blond hair");
    assertThat(character.path("characterStats").asText())
        .contains("Age range: 18-80", "Species: Human");
    assertThat(character.path("richSummary").asText()).contains("appears in 3 films");
  }

  @Test
  void honorsExtrasScope() throws Exception {
    String query =
        """
        query {
          allSpecies(limit: 1) {
            name
            culturalNotes
            rarityLevel
            specialAbilities
            technologicalLevel
          }
        }
        """;

    JsonNode withoutScope = execute(query, Map.of());
    assertThat(withoutScope.path("errors").isArray()).isTrue();

    JsonNode withScope = execute(query, Map.of("X-Viaduct-Scopes", "extras"));
    assertNoErrors(withScope);
    assertThat(withScope.at("/data/allSpecies/0/name").asText()).isEqualTo("Human");
    assertThat(withScope.at("/data/allSpecies/0/rarityLevel").asText()).isEqualTo("Common");
  }

  @Test
  void enforcesMutationSecurityAndExecutesTypedOperations() throws Exception {
    JsonNode queryResponse = execute("query { characterSummaryByName(name: \"Luke\") }", Map.of());
    assertNoErrors(queryResponse);
    assertThat(queryResponse.at("/data/characterSummaryByName").asText())
        .isEqualTo("Luke Skywalker (19BBY)");

    String mutation = "mutation { createCharacter(input: { name: \"Ahsoka Tano\" }) { id name } }";
    JsonNode denied = execute(mutation, Map.of());
    assertThat(denied.path("errors").isArray()).isTrue();

    JsonNode created = execute(mutation, Map.of("security-access", "admin"));
    assertNoErrors(created);
    assertThat(created.at("/data/createCharacter/name").asText()).isEqualTo("Ahsoka Tano");

    String characterId = created.at("/data/createCharacter/id").asText();
    JsonNode renamed =
        execute(
            "mutation { renameCharacterSummary(id: \"%s\", name: \"Ahsoka\") }"
                .formatted(characterId),
            Map.of("security-access", "admin"));
    assertNoErrors(renamed);
    assertThat(renamed.at("/data/renameCharacterSummary").asText())
        .isEqualTo("Ahsoka (Unknown birth year)");
  }

  @Test
  void validatesMutationLifecycleAndFailurePaths() throws Exception {
    JsonNode filmResponse = execute("query { allFilms(limit: 1) { id } }", Map.of());
    assertNoErrors(filmResponse);
    String filmId = filmResponse.at("/data/allFilms/0/id").asText();

    JsonNode created =
        execute(
            "mutation { createCharacter(input: { name: \"Contract Test Character\" }) { id name }"
                + " }",
            Map.of("security-access", "admin"));
    assertNoErrors(created);
    String characterId = created.at("/data/createCharacter/id").asText();

    String addMutation =
        """
        mutation {
          addCharacterToFilm(input: { filmId: "%s", characterId: "%s" }) {
            film { title }
            character { name filmCount }
          }
        }
        """
            .formatted(filmId, characterId);
    JsonNode added = execute(addMutation, Map.of("security-access", "admin"));
    assertNoErrors(added);
    assertThat(added.at("/data/addCharacterToFilm/film/title").asText()).isEqualTo("A New Hope");
    assertThat(added.at("/data/addCharacterToFilm/character/filmCount").asInt()).isEqualTo(1);

    JsonNode duplicate = execute(addMutation, Map.of("security-access", "admin"));
    assertHasErrors(duplicate);

    String deleteMutation = "mutation { deleteCharacter(id: \"%s\") }".formatted(characterId);
    JsonNode denied = execute(deleteMutation, Map.of());
    assertHasErrors(denied);

    JsonNode deleted = execute(deleteMutation, Map.of("security-access", "admin"));
    assertNoErrors(deleted);
    assertThat(deleted.at("/data/deleteCharacter").asBoolean()).isTrue();

    JsonNode missingNode =
        execute(
            "query { node(id: \"%s\") { ... on Character { name } } }".formatted(characterId),
            Map.of());
    assertHasErrors(missingNode);
    JsonNode deletedNode = missingNode.path("data").path("node");
    assertThat(deletedNode.isMissingNode() || deletedNode.isNull()).isTrue();

    JsonNode filmAfterDelete =
        execute(
            """
            query {
              node(id: "%s") {
                ... on Film { characters { name } }
              }
            }
            """
                .formatted(filmId),
            Map.of());
    assertNoErrors(filmAfterDelete);
    assertThat(filmAfterDelete.at("/data/node/characters").findValuesAsText("name"))
        .doesNotContain("Contract Test Character");

    JsonNode invalidId =
        execute(
            "mutation { updateCharacterName(id: \"not-a-global-id\", name: \"Invalid\") { id } }",
            Map.of("security-access", "admin"));
    assertHasErrors(invalidId);
  }

  @Test
  void removesAllFilmRelationshipsForDeletedCharacter() {
    CharacterFilmsRepository repository = new CharacterFilmsRepository();
    repository.addCharacterToFilm("6", "1");

    repository.removeCharacter("6");

    assertThat(repository.findFilmsByCharacterId("6")).isEmpty();
    assertThat(repository.findFilmsByCharacterId("1")).containsExactly("1", "2", "3");
  }

  @Test
  void preservesCharacterTimestampsWhenRenaming() {
    Instant created = Instant.parse("2024-01-01T00:00:00Z");
    Instant edited = Instant.parse("2024-02-01T00:00:00Z");
    Character character =
        new Character(
            "1",
            "Leia Organa",
            "19BBY",
            "brown",
            "female",
            "brown",
            150,
            49f,
            "2",
            "1",
            created,
            edited);

    Character renamed = character.withName("General Leia Organa");

    assertThat(renamed.name()).isEqualTo("General Leia Organa");
    assertThat(renamed.created()).isEqualTo(created);
    assertThat(renamed.edited()).isEqualTo(edited);
  }

  private JsonNode execute(String query, Map<String, String> headers) throws Exception {
    return execute(query, Map.of(), headers);
  }

  private JsonNode execute(String query, Map<String, Object> variables, Map<String, String> headers)
      throws Exception {
    Map<String, Object> body = Map.of("query", query, "variables", variables);
    MutableHttpRequest<Map<String, Object>> request =
        HttpRequest.POST("/graphql", body).contentType(MediaType.APPLICATION_JSON_TYPE);
    for (Map.Entry<String, String> header : headers.entrySet()) {
      request.header(header.getKey(), header.getValue());
    }
    String responseBody = client.toBlocking().retrieve(request);
    return OBJECT_MAPPER.readTree(responseBody);
  }

  private void assertNoErrors(JsonNode response) {
    assertThat(response.path("errors").isMissingNode() || response.path("errors").isNull())
        .as(response.toPrettyString())
        .isTrue();
  }

  private void assertHasErrors(JsonNode response) {
    assertThat(response.path("errors").isArray()).as(response.toPrettyString()).isTrue();
  }
}
