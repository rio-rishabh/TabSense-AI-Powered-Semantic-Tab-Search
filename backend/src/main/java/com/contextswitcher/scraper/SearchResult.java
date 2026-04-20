package com.contextswitcher.scraper;

import java.util.List;

public record SearchResult(String answer, List<Citation> citations) {}
