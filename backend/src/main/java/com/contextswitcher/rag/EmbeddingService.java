package com.contextswitcher.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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
    private final int maxAttempts;
    private final long initialBackoffMs;

    public EmbeddingService(
            @Value("${gemini.api-key:}") String apiKey,
            @Value("${gemini.embedding-model:text-embedding-004}") String embeddingModelId,
            @Value("${app.rag.gemini-embed-max-attempts:8}") int maxAttempts,
            @Value("${app.rag.gemini-embed-initial-backoff-ms:900}") long initialBackoffMs) {
        this.apiKey = apiKey;
        this.embeddingModelId = embeddingModelId;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = Math.max(100, initialBackoffMs);
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

        EmbedResponse response = callGeminiWithRetry(request);

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

    private EmbedResponse callGeminiWithRetry(EmbedRequest request) {
        long backoffMs = initialBackoffMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                EmbedResponse response = webClient
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
                return response;
            } catch (WebClientResponseException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxAttempts) {
                    long waitMs = backoffFor429(e, backoffMs);
                    log.warn(
                            "Gemini embedContent rate limited (429), retry {}/{} after {} ms",
                            attempt,
                            maxAttempts,
                            waitMs);
                    sleepQuietly(waitMs);
                    backoffMs = Math.min((long) (backoffMs * 1.6), 25_000);
                    continue;
                }
                log.error("Gemini embedContent failed: HTTP {}", e.getStatusCode().value());
                throw new RuntimeException("Embedding request failed", e);
            } catch (Exception e) {
                log.error("Gemini embedContent failed: {}", e.getMessage());
                throw new RuntimeException("Embedding request failed", e);
            }
        }
        throw new IllegalStateException("embed retry loop fell through");
    }

    private static long backoffFor429(WebClientResponseException e, long defaultMs) {
        String ra = e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (ra != null) {
            try {
                long seconds = Long.parseLong(ra.trim());
                return Math.min(Math.max(seconds * 1000, 200), 60_000);
            } catch (NumberFormatException ignored) {
                // Retry-After can be an HTTP-date; fall back to default backoff
            }
        }
        return defaultMs;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during embedding backoff", ie);
        }
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
