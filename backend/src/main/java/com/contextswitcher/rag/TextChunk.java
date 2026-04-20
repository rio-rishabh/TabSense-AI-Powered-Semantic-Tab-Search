package com.contextswitcher.rag;

/**
 * One chunk of text with metadata for citation and "Jump to tab".
 */
public record TextChunk(String text, String url, String tabId) {}
