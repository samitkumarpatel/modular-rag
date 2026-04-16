package net.samitkumar.modular_rag;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
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
	ChatClient chatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
		var ragAdvisor = RetrievalAugmentationAdvisor.builder()
				.documentRetriever(new MockDocumentRetriever())
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
