package io.chatbots.olx.grabber;

import io.chatbots.olx.grabber.parser.Parser;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
public class OlxGrabberImpl implements OlxGrabber {

    @Autowired
    private Map<String, Parser> parsers;

    public OlxGrabberImpl(Map<String, Parser> parsers) {
        this.parsers = parsers;
    }

    @Override
    @SneakyThrows
    public List<Offer> getOffers(String url) {
        val site = new URI(url).getHost();
        return parsers.entrySet().stream()
                .filter(it -> site.contains(it.getKey()))
                .findFirst()
                .map(Map.Entry::getValue)
                .map(it -> it.parse(url))
                .orElseGet(() -> {
                    log.warn("No parser for requested url:{}", url);
                    return Collections.emptyList();
                });
    }
}
