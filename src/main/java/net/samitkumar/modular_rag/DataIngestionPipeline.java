package net.samitkumar.modular_rag;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataIngestionPipeline {
    final VectorStore vectorStore;

    @Value("classpath:documents/little-riding-hood.md")
    Resource file1;

    @Value("https://raw.githubusercontent.com/samitkumarpatel/samitkumarpatel.github.io/refs/heads/main/README.md")
    UrlResource  file2;

    @PostConstruct
    void run() {
        log.info("Starting data ingestion pipeline ...");

        var markdownReader1 = new MarkdownDocumentReader(file1, MarkdownDocumentReaderConfig.builder()
                .withAdditionalMetadata("about","Little Riding Hood")
                .build());

        var markdownReader2 = new MarkdownDocumentReader(file2, MarkdownDocumentReaderConfig.builder()
                .withAdditionalMetadata("about","Samit Kumar Patel")
                .withAdditionalMetadata("about","samit")
                .build());

        var tokenTextSplitter = TokenTextSplitter.builder().build();

        List<Document> documents = new ArrayList<>();
        documents.addAll(tokenTextSplitter.split(markdownReader1.get()));
        documents.addAll(tokenTextSplitter.split(markdownReader2.get()));

        vectorStore.add(documents);
    }
}
