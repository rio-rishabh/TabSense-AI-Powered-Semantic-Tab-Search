package com.contextswitcher.scraper;

public class PlaywrightScraperService {
    private final Logger log = LoggerFactory.getLogger(PlaywrightScraperService.class);
    @Value("${app.scraper.page-timeout:30000}")
    private int pageTimeoutMs;

    @Value("${app.scraper.max-concurrent-pages:5}")
    private int maxConcurrentPages;

    public List<TabContent> scrapeTabs(List<TabInput> tabs){
        if(tabs == null || tabs.isEmpty()){
            return List.of();
        }

        List<TabContent> results = new ArrayList<>();
        try(Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            try{
                for(TabInput tab : tabs){
                    TabContent content = scrapeOne(browser, tab);
                    results.add(content);
                }
            }
            finally{
                browser.close();
            }
        }
        catch(Exception e){
            log.error("Playwright Scraper Service Error: {}", e.getMessage());
            throw new RuntimeException("Failed to scrape tabs", e);
        }
        return results;
    }

    private TabContent scrapeOne(Browser browser, TabInput tab){
        Page page = null;
        try{
            page = browser.newPage();
            page.setDefaultNavigationTimeout(pageTimeoutMs);
            page.setDefaultTimeout(pageTimeoutMs);
            page.navigate(tab.url());
            String title = page.title();
            String bodyText = "";
            try{
                bodyText = page.innerText("body");
            }
            catch(Exception e){
                log.debug("Could not get body text for {}: {}", tab.url(), e.getMessage());
            }
            if(bodText == null) bodyText = "";
            return new TabContent(tab.url(), title != null ? title : "", bodyText, tab.tabId());
        } catch(Exception e){
            log.warn("failed to scrape {} : {} ", tab.url(), e.getMessage());
            return new TabContent(tab.url(), "", "". tab.tabId());
        }
        finally{
            if(page != null) page.close();
        }
    }
}