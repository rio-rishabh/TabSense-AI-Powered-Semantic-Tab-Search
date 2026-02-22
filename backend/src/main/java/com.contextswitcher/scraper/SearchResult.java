package com.contextswitcher.scraper;

import java.util.List;
public class SearchResult {
    public record SearchResult(String answer, List<Citation> citations){}
}