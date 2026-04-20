package com.contextswitcher.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Calls Google AI <a href="https://ai.google.dev/api/rest/v1beta/models.embedContent">embedContent</a>
 * to turn text into vectors for semantic search. LangChain4j 0.34's Gemini module does not ship an
 * embedding model in this project, so we use the REST API directly via WebClient.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta";

    private final WebClient webClient;
    private final String apiKey;
    private final String embeddingModelId;

    public EmbeddingService(
            @Value("${gemini.api-key:}") String apiKey,
            @Value("${gemini.embedding-model:text-embedding-004}") String embeddingModelId) {
        this.apiKey = apiKey;
        this.embeddingModelId = embeddingModelId;
        this.webClient = WebClient.builder().baseUrl(GEMINI_BASE).build();
    }

    /**
     * Embedding optimized for text stored in the index (tab chunks).
     */
    public float[] embedDocument(String text) {
        return embed(text, "RETRIEVAL_DOCUMENT");
    }

    /**
     * Embedding optimized for user search questions.
     */
    public float[] embedQuery(String text) {
        return embed(text, "RETRIEVAL_QUERY");
    }

    private float[] embed(String text, String taskType) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Set gemini.api-key or GEMINI_API_KEY for embeddings");
        }

        String modelResource = "models/" + embeddingModelId;
        EmbedRequest request = new EmbedRequest(
                modelResource,
                new EmbedContent(new EmbedPart[] {new EmbedPart(text)}),
                taskType);

        EmbedResponse response;
        try {
            response = webClient
                    .post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/models/{model}:embedContent")
                            .queryParam("key", apiKey)
                            .build(embeddingModelId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(EmbedResponse.class)
                    .block();
        } catch (Exception e) {
            log.error("Gemini embedContent failed: {}", e.getMessage());
            throw new RuntimeException("Embedding request failed", e);
        }

        if (response == null                || response.embedding() == null
                || response.embedding().values() == null
                || response.embedding().values().isEmpty()) {
            throw new IllegalStateException("Empty embedding from Gemini");
        }

        List<Double> values = response.embedding().values();
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i).floatValue();
        }
        return out;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record EmbedRequest(String model, EmbedContent content, String taskType) {}

    private record EmbedContent(EmbedPart[] parts) {}

    private record EmbedPart(String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbedResponse(EmbedPayload embedding) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbedPayload(List<Double> values) {}
}
