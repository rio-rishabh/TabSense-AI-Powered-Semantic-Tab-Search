package com.contextswitcher.rag;

import com.contextswitcher.scraper.TabContent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class ChunkingService {

    @Value("${app.rag.chunk-size:500}")
    private int chunkSize;

    private static final Pattern SPLIT_ON = Pattern.compile("\\n\\n+|\\r\\n|\\n|[.]\\s+");

    /**
     * Splits each tab's body text into chunks of about chunkSize characters,
     * preserving url and tabId on each chunk for later citation.
     */
    public List<TextChunk> chunk(List<TabContent> tabContents) {
        if (tabContents == null || tabContents.isEmpty()) {
            return List.of();
        }
        List<TextChunk> out = new ArrayList<>();
        for (TabContent content : tabContents) {
            String text = content.bodyText();
            if (text == null || text.isBlank()) {
                continue;
            }
            String url = content.url();
            String tabId = content.tabId();
            List<String> parts = splitRecursive(text, chunkSize);
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    out.add(new TextChunk(trimmed, url, tabId));
                }
            }
        }
        return out;
    }

    private List<String> splitRecursive(String text, int maxSize) {
        if (text.length() <= maxSize) {
            return List.of(text);
        }
        String[] candidates = SPLIT_ON.split(text, 2);
        if (candidates.length < 2) {
            return splitBySize(text, maxSize);
        }
        String first = candidates[0].trim();
        String rest = candidates[1];
        List<String> result = new ArrayList<>();
        if (!first.isEmpty()) {
            if (first.length() > maxSize) {
                result.addAll(splitBySize(first, maxSize));
            } else {
                result.add(first);
            }
        }
        result.addAll(splitRecursive(rest, maxSize));
        return result;
    }

    private List<String> splitBySize(String text, int maxSize) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxSize, text.length());
            result.add(text.substring(start, end));
            start = end;
        }
        return result;
    }
}
