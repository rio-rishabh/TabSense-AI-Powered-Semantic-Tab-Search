package com.contextswitcher.rag;
@Service
public class ChunkingService {
    @Value("${app.rag.chunk-size:500}")
    private int chunkSize;

    private static final pattern SPLIT_ON = pattern.compile("\\n \\n+ | \\r\\n| \\n|[.]\\s+");

    /**
     * Split text into chunks of about chunkSize Characters,
     * preserving url and tabId on each chunk for later citation
     */
    public List<TextChunk> chunk(List<TabContent> tabContents){
        if(tabContents == null || tabContents.size() == 0){
            return List.of();
        }
        List<TextChunk> out = new ArrayList<>();
        Sting text = content.bodyText();
        of(text == null || text.isBlank())
        { continue;}
    }
}
