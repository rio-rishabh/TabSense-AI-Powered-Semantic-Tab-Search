package com.contextswitcher.cache;

import com.contextswitcher.scraper.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Cache-aside search results in Redis with a TTL. Uses a monotonically increasing sync version so
 * {@code syncTabs} invalidates prior cached answers without scanning keys.
 */
@Component
public class RedisSearchCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSearchCache.class);

    private static final String SYNC_VERSION_KEY_SUFFIX = ":rag:sync_version";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration ttl;
    private final String keyPrefix;

    public RedisSearchCache(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectMapper objectMapper,
            @Value("${app.cache.redis.enabled:false}") boolean enabled,
            @Value("${app.cache.redis.ttl-seconds:600}") long ttlSeconds,
            @Value("${app.cache.redis.key-prefix:ctxswitch}") String keyPrefix) {
        this.redis = redisProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.enabled = enabled && this.redis != null;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.keyPrefix = keyPrefix;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Call after a successful sync so cached searches from older corpora are not reused. */
    public void bumpSyncVersion() {
        if (!enabled || redis == null) {
            return;
        }
        try {
            Long v = redis.opsForValue().increment(syncVersionKey());
            log.debug("Redis search cache sync version now {}", v);
        } catch (Exception e) {
            log.warn("Failed to bump Redis sync version: {}", e.getMessage());
        }
    }

    public Optional<SearchResult> get(String cacheFingerprint) {
        if (!enabled || redis == null) {
            return Optional.empty();
        }
        String key = searchKey(cacheFingerprint);
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, SearchResult.class));
        } catch (Exception e) {
            log.warn("Redis search cache get failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String cacheFingerprint, SearchResult result) {
        if (!enabled || redis == null) {
            return;
        }
        String key = searchKey(cacheFingerprint);
        try {
            String json = objectMapper.writeValueAsString(result);
            redis.opsForValue().set(key, json, ttl);
            log.debug("Redis search cache put key={} ttl={}", key, ttl);
        } catch (Exception e) {
            log.warn("Redis search cache put failed for key {}: {}", key, e.getMessage());
        }
    }

    /**
     * Stable fingerprint for cache key: hash of normalized question + model knobs so different
     * configs do not collide.
     */
    /** {@code generationModel} is the OpenRouter model id when using OpenRouter, else the Gemini chat model name. */
    public static String fingerprint(String question, String llmProvider, String generationModel, int topK) {
        String normalized = question == null ? "" : question.trim().toLowerCase();
        String payload =
                normalized + "|" + (llmProvider == null ? "" : llmProvider) + "|" + (generationModel == null ? "" : generationModel) + "|" + topK;
        return sha256Hex(payload);
    }

    private String searchKey(String fingerprint) {
        return keyPrefix + ":search:v" + currentSyncVersion() + ":" + fingerprint;
    }

    private String syncVersionKey() {
        return keyPrefix + SYNC_VERSION_KEY_SUFFIX;
    }

    private String currentSyncVersion() {
        try {
            String v = redis.opsForValue().get(syncVersionKey());
            return v == null || v.isBlank() ? "0" : v;
        } catch (Exception e) {
            log.warn("Failed to read sync version from Redis: {}", e.getMessage());
            return "0";
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
