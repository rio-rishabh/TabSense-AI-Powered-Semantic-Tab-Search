package com.contextswitcher.rag;

import com.contextswitcher.scraper.Citation;
import com.contextswitcher.scraper.PlaywrightScraperService;
import com.contextswitcher.scraper.SearchResult;
import com.contextswitcher.scraper.TabContent;
import com.contextswitcher.scraper.TabInput;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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

    private volatile ChatLanguageModel chatModel;

    public RagService(
            PlaywrightScraperService scraper,
            ChunkingService chunkingService,
            EmbeddingService embeddingService,
            VectorStore vectorStore,
            @Value("${gemini.api-key:}") String apiKey,
            @Value("${gemini.model-name:gemini-1.5-flash}") String chatModelName,
            @Value("${app.rag.top-k:5}") int topK) {
        this.scraper = scraper;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.apiKey = apiKey;
        this.chatModelName = chatModelName;
        this.topK = topK;
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
            } catch (Exception e) {
                log.warn("Skip chunk (embed failed) for tab {}: {}", chunk.tabId(), e.getMessage());
            }
        }
        log.info("Sync complete: {} chunks indexed from {} tabs", embedded, contents.size());
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

        float[] queryVector = embeddingService.embedQuery(question);
        List<VectorStore.SearchHit> hits = vectorStore.findMostSimilar(queryVector, topK);
        if (hits.isEmpty()) {
            return new SearchResult("No relevant passages found in your synced tabs.", List.of());
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

        ChatLanguageModel model = chatModel();
        Response<AiMessage> response = model.generate(List.of(system, user));
        String answer = response.content().text();

        List<Citation> citations = new ArrayList<>();
        for (VectorStore.SearchHit hit : hits) {
            TextChunk ch = hit.chunk();
            citations.add(new Citation(ch.tabId(), ch.url(), snippet(ch.text())));
        }
        return new SearchResult(answer, citations);
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
}

