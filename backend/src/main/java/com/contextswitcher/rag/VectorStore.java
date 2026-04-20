package com.contextswitcher.rag;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * In-memory vector index: each {@link TextChunk} plus its embedding. Used for Phase 1 before ChromaDB.
 * <p>
 * Typical use from {@code RagService}:
 * <ol>
 *   <li>On sync: {@link #clear()}, then {@link #add(TextChunk, float[])} for each embedded chunk.</li>
 *   <li>On search: {@link #findMostSimilar(float[], int)} with {@link EmbeddingService#embedQuery(String)}.</li>
 * </ol>
 */
@Service
public class VectorStore {

    private final List<VectorEntry> entries = new ArrayList<>();

    /** Remove all indexed chunks (e.g. before re-sync). */
    public synchronized void clear() {
        entries.clear();
    }

    /** Store one chunk and its embedding vector (same dimension for all entries). */
    public synchronized void add(TextChunk chunk, float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding must be non-empty");
        }
        entries.add(new VectorEntry(chunk, embedding.clone()));
    }

    /**
     * Returns up to {@code topK} chunks with highest cosine similarity to {@code queryEmbedding}.
     */
    public synchronized List<SearchHit> findMostSimilar(float[] queryEmbedding, int topK) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            throw new IllegalArgumentException("queryEmbedding must be non-empty");
        }
        if (entries.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<SearchHit> scored = new ArrayList<>(entries.size());
        for (VectorEntry e : entries) {
            if (e.embedding().length != queryEmbedding.length) {
                continue;
            }
            double sim = cosineSimilarity(queryEmbedding, e.embedding());
            scored.add(new SearchHit(e.chunk(), sim));
        }
        scored.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        int limit = Math.min(topK, scored.size());
        return scored.subList(0, limit);
    }

    public synchronized int size() {
        return entries.size();
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record VectorEntry(TextChunk chunk, float[] embedding) {}

    /** Chunk plus similarity score in [0, 1] for cosine similarity. */
    public record SearchHit(TextChunk chunk, double score) {}
}
