package com.contextswitcher.rag;

import com.contextswitcher.cache.RedisSearchCache;
import com.contextswitcher.scraper.Citation;
import com.contextswitcher.scraper.PlaywrightScraperService;
import com.contextswitcher.scraper.SearchResult;
import com.contextswitcher.scraper.TabContent;
import com.contextswitcher.scraper.TabInput;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final int SNIPPET_MAX = 400;

    private final PlaywrightScraperService scraper;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    private final String apiKey;
    private final String chatModelName;
    private final int topK;
    private final String llmProvider;
    private final String openRouterApiKey;
    private final String openRouterModel;
    private final double openRouterTemperature;
    private final WebClient openRouterClient;
    private final RedisSearchCache searchCache;
    private final long embedChunkDelayMs;

    private volatile ChatLanguageModel chatModel;

    public RagService(
            PlaywrightScraperService scraper,
            ChunkingService chunkingService,
            EmbeddingService embeddingService,
            VectorStore vectorStore,
            @Value("${gemini.api-key:}") String apiKey,
            @Value("${gemini.model-name:gemini-1.5-flash}") String chatModelName,
            @Value("${app.rag.top-k:5}") int topK,
            @Value("${llm.provider:gemini}") String llmProvider,
            @Value("${openrouter.api-key:}") String openRouterApiKey,
            @Value("${openrouter.base-url:https://openrouter.ai/api/v1}") String openRouterBaseUrl,
            @Value("${openrouter.model:openai/gpt-4o-mini}") String openRouterModel,
            @Value("${openrouter.temperature:0.2}") double openRouterTemperature,
            @Value("${app.rag.embed-chunk-delay-ms:120}") long embedChunkDelayMs,
            RedisSearchCache searchCache) {
        this.scraper = scraper;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.apiKey = apiKey;
        this.chatModelName = chatModelName;
        this.topK = topK;
        this.llmProvider = llmProvider;
        this.openRouterApiKey = openRouterApiKey;
        this.openRouterModel = openRouterModel;
        this.openRouterTemperature = openRouterTemperature;
        this.openRouterClient = WebClient.builder()
                .baseUrl(openRouterBaseUrl)
                .build();
        this.searchCache = searchCache;
        this.embedChunkDelayMs = Math.max(0, embedChunkDelayMs);
    }

    /**
     * Scrape tabs, chunk text, embed each chunk, and replace the in-memory vector index.
     */
    public void syncTabs(List<TabInput> tabs) {
        List<TabContent> contents = scraper.scrapeTabs(tabs);
        List<TextChunk> chunks = chunkingService.chunk(contents);
        vectorStore.clear();
        int embedded = 0;
        for (TextChunk chunk : chunks) {
            try {
                float[] vector = embeddingService.embedDocument(chunk.text());
                vectorStore.add(chunk, vector);
                embedded++;
                sleepBetweenEmbeds();
            } catch (Exception e) {
                log.warn("Skip chunk (embed failed) for tab {}: {}", chunk.tabId(), e.getMessage());
            }
        }
        log.info("Sync complete: {} chunks indexed from {} tabs", embedded, contents.size());
        searchCache.bumpSyncVersion();
    }

    /**
     * Retrieve top similar chunks, call Gemini with context, return answer and citations.
     */
    public SearchResult search(String question) {
        if (question == null || question.isBlank()) {
            return new SearchResult("Ask a non-empty question.", List.of());
        }
        if (vectorStore.size() == 0) {
            return new SearchResult(
                    "No content indexed yet. Run sync with your open tabs first.", List.of());
        }

        final float[] queryVector;
        try {
            queryVector = embeddingService.embedQuery(question);
        } catch (Exception e) {
            log.warn("Query embedding failed: {}", e.toString());
            return new SearchResult(
                    "Could not embed your question: the Gemini embedding API failed (often HTTP 429 rate limit "
                            + "or daily quota). Wait a few minutes, sync fewer tabs at once, or check usage and "
                            + "billing in Google AI Studio. OpenRouter chat is unaffected; only embeddings use Gemini.",
                    List.of());
        }
        List<VectorStore.SearchHit> hits = vectorStore.findMostSimilar(queryVector, topK);
        if (hits.isEmpty()) {
            return new SearchResult("No relevant passages found in your synced tabs.", List.of());
        }

        String generationModel =
                "openrouter".equalsIgnoreCase(llmProvider) ? openRouterModel : chatModelName;
        String fingerprint = RedisSearchCache.fingerprint(question, llmProvider, generationModel, topK);
        Optional<SearchResult> cached = searchCache.get(fingerprint);
        if (cached.isPresent()) {
            log.debug("Search cache hit for fingerprint prefix {}", fingerprint.substring(0, Math.min(12, fingerprint.length())));
            return cached.get();
        }

        String contextBlock = buildContextBlock(hits);
        SystemMessage system = SystemMessage.from(
                "You are a helpful assistant. Answer the user's question using ONLY the provided "
                        + "context from their browser tabs. If the context does not contain the answer, say so. "
                        + "Be concise. You may refer to sources by their bracket numbers [1], [2], etc.");
        UserMessage user = UserMessage.from(
                "Context from synced tabs:\n\n"
                        + contextBlock
                        + "\n\nQuestion: "
                        + question);

        String answer = generateAnswer(system.text(), user.text());

        List<Citation> citations = new ArrayList<>();
        for (VectorStore.SearchHit hit : hits) {
            TextChunk ch = hit.chunk();
            citations.add(new Citation(ch.tabId(), ch.url(), snippet(ch.text())));
        }
        SearchResult result = new SearchResult(answer, citations);
        searchCache.put(fingerprint, result);
        return result;
    }

    private void sleepBetweenEmbeds() {
        if (embedChunkDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(embedChunkDelayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Sync interrupted during embed spacing delay");
        }
    }

    private String buildContextBlock(List<VectorStore.SearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            TextChunk ch = hits.get(i).chunk();
            sb.append("[")
                    .append(i + 1)
                    .append("] tabId=")
                    .append(ch.tabId())
                    .append(" url=")
                    .append(ch.url())
                    .append("\n")
                    .append(ch.text())
                    .append("\n\n");
        }
        return sb.toString();
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() <= SNIPPET_MAX) {
            return t;
        }
        return t.substring(0, SNIPPET_MAX) + "…";
    }

    private ChatLanguageModel chatModel() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Set gemini.api-key or GEMINI_API_KEY for chat");
        }
        ChatLanguageModel existing = chatModel;
        if (existing == null) {
            synchronized (this) {
                existing = chatModel;
                if (existing == null) {
                    chatModel = GoogleAiGeminiChatModel.builder()
                            .apiKey(apiKey)
                            .modelName(chatModelName)
                            .build();
                    existing = chatModel;
                }
            }
        }
        return existing;
    }

    private String generateAnswer(String systemPrompt, String userPrompt) {
        if ("openrouter".equalsIgnoreCase(llmProvider)) {
            return generateWithOpenRouter(systemPrompt, userPrompt);
        }
        ChatLanguageModel model = chatModel();
        Response<AiMessage> response = model.generate(List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)
        ));
        return response.content().text();
    }

    private String generateWithOpenRouter(String systemPrompt, String userPrompt) {
        if (openRouterApiKey == null || openRouterApiKey.isBlank()) {
            throw new IllegalStateException("Set openrouter.api-key or OPENROUTER_API_KEY for OpenRouter chat");
        }
        OpenRouterRequest request = new OpenRouterRequest(
                openRouterModel,
                openRouterTemperature,
                List.of(
                        new OpenRouterMessage("system", systemPrompt),
                        new OpenRouterMessage("user", userPrompt)
                ));

        OpenRouterResponse response = openRouterClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + openRouterApiKey)
                .header("HTTP-Referer", "http://localhost:8080")
                .header("X-Title", "ContextSwitcher")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OpenRouterResponse.class)
                .block();

        if (response == null
                || response.choices() == null
                || response.choices().isEmpty()
                || response.choices().get(0).message() == null
                || response.choices().get(0).message().content() == null) {
            throw new RuntimeException("OpenRouter returned empty response");
        }
        return response.choices().get(0).message().content();
    }

    private record OpenRouterRequest(String model, double temperature, List<OpenRouterMessage> messages) {}

    private record OpenRouterMessage(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenRouterResponse(List<OpenRouterChoice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenRouterChoice(OpenRouterMessage message) {}
}

