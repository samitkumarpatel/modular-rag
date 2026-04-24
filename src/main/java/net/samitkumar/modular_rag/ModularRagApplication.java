package net.samitkumar.modular_rag;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.util.StringUtils.hasText;
import static org.springframework.web.servlet.function.RequestPredicates.contentType;

@SpringBootApplication
public class ModularRagApplication {

	public static void main(String[] args) {
		SpringApplication.run(ModularRagApplication.class, args);
	}

	@Bean
	RestClient  restClient(RestClient.Builder restClientBuilder) {
		return restClientBuilder
				.build();
	}

	@Bean
	ChatClient chatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory, VectorStore vectorStore) {
		var ragAdvisor = RetrievalAugmentationAdvisor.builder()
				//If a embedding model does not support multilanguage we can set a query transformer to translate the query to the language supported by the embedding model. For example, if the embedding model only supports English, we can translate the query to English before generating the embedding.
				.queryTransformers(

						//add a web search engine query transformer to fetch relevant documents from the web and add them to the retrieved documents. This can help to improve the retrieval performance by providing more relevant documents to the retriever. For example, if the user asks a question about a recent event, we can use a websearch engine query transformer to fetch relevant news articles and add them to the retrieved documents.
						/*RewriteQueryTransformer.builder()
								.chatClientBuilder(chatClientBuilder.clone())
								.targetSearchSystem("wikipedia")
								.build(),*/

						//Translating purpose
						TranslationQueryTransformer.builder()
						.chatClientBuilder(chatClientBuilder.clone())
						.targetLanguage("english")
						.build())
				// Retrieve from small docs or mock
				//.documentRetriever(new MockDocumentRetriever())
				.documentRetriever(VectorStoreDocumentRetriever.builder()
						.vectorStore(vectorStore)
						.build())
				//add a post processor to filter the retrieved documents based on the metadata. For example, if the user asks a question about a specific topic, we can filter the retrieved documents to only include those that are relevant to that topic.
				.documentPostProcessors(
						(documents, query) -> query.stream()
								.filter(doc -> doc.getMetadata().getOrDefault("about", "").toString().toLowerCase().contains("samit"))
								.toList()
				)
				//.documentPostProcessors(MyDocumentsPostProcessor.builder().build())
				.queryExpander(MultiQueryExpander.builder()
						.chatClientBuilder(chatClientBuilder.clone())
						.numberOfQueries(3)
						.build())
				.build();

		return chatClientBuilder
				.defaultAdvisors(
						ragAdvisor,
						MessageChatMemoryAdvisor.builder(chatMemory).build()
				)
				.build();
	}

	@Bean
	RouterFunction<ServerResponse> routerFunction(ChatHandler chatHandler) {
		return RouterFunctions
				.route()
				.POST("/chat/stream", contentType(MediaType.APPLICATION_JSON), chatHandler::chatStream)
				.POST("/chat", chatHandler::chat)
				.build();
	}
}

@Builder
@RequiredArgsConstructor
@Component
class MyDocumentsPostProcessor implements DocumentPostProcessor {
	final ChatClient chatClient;
	final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""
					Given the following contextual information and input query, your task is to synthesize a compresses version of the context that is relevant to answer the input query, reduce noise and redundancy
					
					Contextual Information:
					{context}
					
					User query:
					{query}
					
					Compressed contextual information:
					""");

	@Override
	public List<Document> process(Query query, List<Document> list) {
			var context = list.stream().map(Document::getText).reduce("", (a, b) -> a + "\n" + b);
			var prompt = DEFAULT_PROMPT_TEMPLATE.create(Map.of("context", context, "query", query.text()));
			var compressedContext = chatClient.prompt(prompt).call().content();
			//We can return a new document with the compressed context or we can update the existing documents with the compressed context. Here we are returning a new document with the compressed context.
			var compressedDocument = Document.builder().text(compressedContext).metadata(Map.of("compressed", "true")).build();
			return List.of(compressedDocument);
	}
}

class MockDocumentRetriever implements DocumentRetriever {

	@Override
	public List<Document> retrieve(Query query) {
		return List.of(
				Document.builder().metadata(Map.of("about", "Samit")).text("I'm Samit. I'm 50 Yrs Old. I don't sing and Dance ").build()
		);
	}
}

@Component
@RequiredArgsConstructor
class ChatHandler {
	private final ChatClient chatClient;

	public ServerResponse chat(ServerRequest request) {
		var body = extractBody(request);
		var question = body.get("question");
		var id = body.get("id");

		if(!hasText(question)) {
			return ServerResponse.badRequest().body("Missing 'question' field in request body");
		}

		var llmReply = chatClient
				.prompt(question)
				.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, id))
				.call()
				.content();
		return  ServerResponse.ok().body(Map.of("id", id, "answer", llmReply));
	}

	@SneakyThrows
	Map<String, String> extractBody(ServerRequest request) {
		var body = request.body(Map.class);
		assert body.isEmpty() : "Expected a JSON body with 'question' and optional 'id' fields";
		var question = body.computeIfAbsent("question", k -> "").toString();
		var id = body.computeIfAbsent("id", k -> UUID.randomUUID()).toString();
		return Map.of("question", question, "id", id);
	}

	public ServerResponse chatStream(ServerRequest request) {
		var body = extractBody(request);
		var question = body.get("question");
		var id = body.get("id");

		if(!hasText(question)) {
			return ServerResponse.badRequest().body("Missing 'question' field in request body");
		}

		var flux = chatClient
				.prompt(question)
				.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, id))
				.stream()
				.content();

		return ServerResponse
				.ok()
				.contentType(MediaType.APPLICATION_NDJSON)
				.header("X-Conversation-ID", id)
				.stream(s -> flux.subscribe(token -> {
					token = token.replace("\"", "\\\"");
					try {
						s.write(token).flush();
					} catch (Exception e) {s.error(e);}
				}, s::error, s::complete));
	}
}
