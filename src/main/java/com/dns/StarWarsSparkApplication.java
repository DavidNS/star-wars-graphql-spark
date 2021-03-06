package com.dns;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.dns.controller.GraphQLController;
import com.dns.repository.CharacterRepository;
import com.dns.repository.StarshipRepository;
import com.dns.resolver.CharacterResolver;
import com.dns.resolver.StarshipResolver;
import com.dns.service.CharacterService;
import com.dns.service.StarshipService;
import com.fasterxml.jackson.databind.ObjectMapper;

import graphql.GraphQL;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.com.google.common.base.Charsets;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.dataloader.DataLoaderDispatcherInstrumentation;
import graphql.execution.instrumentation.dataloader.DataLoaderDispatcherInstrumentationOptions;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.TypeRuntimeWiring;
import spark.Spark;

public class StarWarsSparkApplication {

	public static void main(String[] args) {
		ObjectMapper objectMapper = new ObjectMapper();

		GraphQLController graphQLController = buildController(objectMapper);
		
		Spark.port(8080);
		Spark.post("/graphql", graphQLController::post, objectMapper::writeValueAsString);
	}

	private static GraphQLController buildController(ObjectMapper objectMapper) {
		StarshipRepository starshipRepository = new StarshipRepository();
		CharacterRepository characterRepository = new CharacterRepository();

		StarshipService starshipService = new StarshipService(starshipRepository, characterRepository);
		CharacterService characterService = new CharacterService(characterRepository, starshipRepository);

		CharacterResolver characterResolver = new CharacterResolver(objectMapper, characterService);
		StarshipResolver starshipResolver = new StarshipResolver(objectMapper, starshipService);

		GraphQL graphQL = buildGraphQL(characterResolver, starshipResolver, objectMapper);
		GraphQLController graphQLController = new GraphQLController(characterResolver, starshipResolver, objectMapper,
				graphQL);
		return graphQLController;
	}

	public static GraphQL buildGraphQL(CharacterResolver characterResolver, StarshipResolver starshipResolver,
			ObjectMapper objectMapper) {
		String stringSchema = readGraphQLSchema();
		TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(stringSchema);

		RuntimeWiring runtimeWiring = buildGraphQLWiring(characterResolver, starshipResolver, objectMapper);
		GraphQLSchema graphQLSchema = new SchemaGenerator().makeExecutableSchema(typeRegistry, runtimeWiring);

		ChainedInstrumentation chainedInstrumentation = buildInstrumentation();
		
		return GraphQL.newGraphQL(graphQLSchema)
				.instrumentation(chainedInstrumentation)
				.build();
	}

	private static ChainedInstrumentation buildInstrumentation() {
		DataLoaderDispatcherInstrumentationOptions options = DataLoaderDispatcherInstrumentationOptions.newOptions()
				.includeStatistics(true);
		DataLoaderDispatcherInstrumentation dataLoaderDispatcherInstrumentation = new DataLoaderDispatcherInstrumentation(options);
		
		MaxQueryComplexityInstrumentation maxQueryComplexityInstrumentation = new MaxQueryComplexityInstrumentation(100,
				new StarWarsFieldComplexityCalculator());
		MaxQueryDepthInstrumentation maxQueryDepthInstrumentation = new MaxQueryDepthInstrumentation(13);

		List<Instrumentation> chainedList = new ArrayList<>();
		chainedList.add(maxQueryComplexityInstrumentation);
		chainedList.add(maxQueryDepthInstrumentation);
		chainedList.add(dataLoaderDispatcherInstrumentation);
		return new ChainedInstrumentation(chainedList);
	}

	public static String readGraphQLSchema() {
		try {
			URL url = Thread.currentThread().getContextClassLoader().getResource("graphql/schema.graphqls");
			System.out.println(url);
			return Files.readString(Paths.get(url.toURI()), Charsets.UTF_8);
		} catch (Exception e) {
			throw new RuntimeException("Unexpected Error Reading GraphQL Shcema", e);
		}
	}

	// Mapping
	public static RuntimeWiring buildGraphQLWiring(CharacterResolver characterResolver, StarshipResolver starshipResolver, ObjectMapper objectMapper) {
		return RuntimeWiring.newRuntimeWiring()
				.type(TypeRuntimeWiring.newTypeWiring("Query").dataFetcher("getCharacterById", characterResolver::queryGetCharacterById))
				.type(TypeRuntimeWiring.newTypeWiring("Query").dataFetcher("getStarshipById", starshipResolver::queryGetStarshipById))
				.type(TypeRuntimeWiring.newTypeWiring("Mutation").dataFetcher("saveDroidCharacter", characterResolver::mutationSaveDroidCharacter))
				.type(TypeRuntimeWiring.newTypeWiring("Mutation").dataFetcher("saveBiologicalCharacter", characterResolver::mutationSaveBiologicalCharacter))
				.type(TypeRuntimeWiring.newTypeWiring("Mutation").dataFetcher("saveStarship", starshipResolver::mutationSaveStarship))
				.type(TypeRuntimeWiring.newTypeWiring("Mutation").dataFetcher("deleteCharacterById", characterResolver::mutationDeleteCharacterById))
				.type(TypeRuntimeWiring.newTypeWiring("Mutation").dataFetcher("deleteAllCharacters", characterResolver::mutationDeleteAllCharacters))
				.type(TypeRuntimeWiring.newTypeWiring("Mutation").dataFetcher("deleteStarshipById", starshipResolver::mutationDeleteStarshipById))
				.type(TypeRuntimeWiring.newTypeWiring("Mutation").dataFetcher("deleteAllStarships", starshipResolver::mutationDeleteAllStarships))
				.type(TypeRuntimeWiring.newTypeWiring("Character").typeResolver(characterResolver::getCharacterTypeResolver))
				.type(TypeRuntimeWiring.newTypeWiring("Biological").dataFetcher("friends", characterResolver::characterFriends))
				.type(TypeRuntimeWiring.newTypeWiring("Droid").dataFetcher("friends", characterResolver::characterFriends))
				.type(TypeRuntimeWiring.newTypeWiring("Biological").dataFetcher("starship", starshipResolver::characterStarship))
				.build();
	}

}
