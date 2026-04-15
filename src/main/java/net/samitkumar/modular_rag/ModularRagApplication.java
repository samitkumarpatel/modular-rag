package net.samitkumar.modular_rag;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;

import static org.springframework.util.StringUtils.hasText;
import static org.springframework.web.servlet.function.RequestPredicates.contentType;

@SpringBootApplication
public class ModularRagApplication {

	public static void main(String[] args) {
		SpringApplication.run(ModularRagApplication.class, args);
	}

	@Bean
	ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
		var ragAdvisor = RetrievalAugmentationAdvisor.builder()
				.documentRetriever(new MockDocumentRetriever())
				.build();
		return chatClientBuilder
				.defaultAdvisors(ragAdvisor)
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
				Document.builder().text("My name is Samit and I'm very Old").build()
		);
	}
}

@Component
@RequiredArgsConstructor
class ChatHandler {
	private final ChatClient chatClient;

	@SneakyThrows
	public ServerResponse chat(ServerRequest request) {
		var question = request
				.body(Map.class)
				.computeIfAbsent("question", k -> "").toString();

		if(!hasText(question)) {
			return ServerResponse.badRequest().body("Missing 'question' field in request body");
		}

		var llmReply = chatClient
				.prompt(question)
				.call()
				.content();
		return  ServerResponse.ok().body(llmReply);
	}

	@SneakyThrows
	public ServerResponse chatStream(ServerRequest request) {
		var question = request
				.body(Map.class)
				.computeIfAbsent("question", k -> "").toString();

		if(!hasText(question)) {
			return ServerResponse.badRequest().body("Missing 'question' field in request body");
		}

		var flux = chatClient
				.prompt(question)
				.stream()
				.content();

		return ServerResponse
				.ok()
				.contentType(MediaType.APPLICATION_NDJSON)
				.stream(s -> flux.subscribe(token -> {
					token = token.replace("\"", "\\\"");
					try {
						s.write(token).flush();
					} catch (Exception e) {s.error(e);}
				}, s::error, s::complete));
	}
}
