package io.fundrequest.platform.faq;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.fundrequest.platform.faq.model.FaqItemDto;
import io.fundrequest.platform.faq.parser.Faqs;
import io.fundrequest.platform.github.GithubGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class FAQServiceImpl implements FAQService {

    private static final int DAY_IN_MILLIS = 86400000;

    private final GithubGateway githubGateway;
    private final String owner;
    private final String repo;
    private final String branch;
    private final String filePath;
    private final XmlMapper xmlMapper;
    private final FaqItemMapper faqItemMapper;
    private final CacheManager cacheManager;

    public FAQServiceImpl(final GithubGateway githubGateway,
                          @Value("${io.fundrequest.faq.owner:FundRequest}") final String owner,
                          @Value("${io.fundrequest.faq.repo:FAQ}") final String repo,
                          @Value("${io.fundrequest.faq.branch:master}") final String branch,
                          @Value("${io.fundrequest.faq.repo:FAQ.xml}") final String filePath,
                          final XmlMapper xmlMapper,
                          final FaqItemMapper faqItemMapper,
                          final CacheManager cacheManager) {
        this.githubGateway = githubGateway;
        this.owner = owner;
        this.repo = repo;
        this.branch = branch;
        this.filePath = filePath;
        this.xmlMapper = xmlMapper;
        this.faqItemMapper = faqItemMapper;
        this.cacheManager = cacheManager;
    }

    @Scheduled(fixedRate = DAY_IN_MILLIS)
    @EventListener(ContextRefreshedEvent.class)
    public void refreshFAQs() {
        final Faqs faqs;
        try {
            faqs = xmlMapper.readValue(githubGateway.getContentsAsRaw(owner, repo, branch, filePath), Faqs.class);
        } catch (IOException e) {
            throw new RuntimeException("Something went wrong while trying to parse FAQ.xml", e);
        }
        faqs.getPages()
            .forEach(page -> cacheManager.getCache("faqs")
                                         .put(page.getName(), faqItemMapper.mapToList(page.getFaqs())));

    }

    @Cacheable(cacheNames = "faqs", key = "#pageName")
    public List<FaqItemDto> getFAQsForPage(final String pageName) {
        // This method is a fallback in case refreshFAQs failed or didn't ran for some reason
        final Faqs faqs;
        try {
            faqs = xmlMapper.readValue(githubGateway.getContentsAsRaw(owner, repo, branch, filePath), Faqs.class);
        } catch (IOException e) {
            throw new RuntimeException("Something went wrong while trying to parse FAQ.xml", e);
        }
        return faqs.getPages()
                   .stream()
                   .filter(page -> page.getName().equalsIgnoreCase(pageName))
                   .findFirst().map(page -> faqItemMapper.mapToList(page.getFaqs()))
                   .orElseThrow(() -> new RuntimeException("No page definition for " + pageName + " found in FAQ.xml"));
    }
}
