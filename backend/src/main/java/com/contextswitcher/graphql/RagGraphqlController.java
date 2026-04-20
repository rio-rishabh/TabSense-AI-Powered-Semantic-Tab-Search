package com.contextswitcher.graphql;

import com.contextswitcher.rag.RagService;
import com.contextswitcher.scraper.SearchResult;
import com.contextswitcher.scraper.TabInput;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
public class RagGraphqlController {

    private final RagService ragService;

    public RagGraphqlController(RagService ragService) {
        this.ragService = ragService;
    }

    @MutationMapping
    public Boolean syncTabs(@Argument List<TabInput> tabs) {
        ragService.syncTabs(tabs);
        return true;
    }

    @QueryMapping
    public SearchResult search(@Argument String query) {
        return ragService.search(query);
    }
}
