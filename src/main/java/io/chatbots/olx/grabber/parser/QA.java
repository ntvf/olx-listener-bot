package io.chatbots.olx.grabber.parser;

import io.chatbots.olx.grabber.Offer;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class QA extends BaseParser implements Parser {
    @Override
    public List<Offer> parse(String url) {
        try {

            if (url.contains("?")) {
                url = url + "&";
            } else {
                url = url + "?";
            }
            url = url + "search%5Border%5D=created_at%3Adesc";

            val body = Jsoup.connect(url).get().body();

            return Optional.ofNullable(body.getElementById("offers_table"))
                    .orElseGet(() -> body.getElementById("results-list"))
                    .select(".ads__item__info")
                    .stream()
                    .map(it -> {
                                val element = it.select(".ads__item__ad--title");
                                String link = element.attr("href");
                                String content = "";
                                String name = element.text();
                                return Offer.builder()
                                        .url(cleanUrlFromQueryParams(link))
                                        .content(content)
                                        .name(name)
                                        .build();
                            }
                    ).collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Error while parsing url:" + url, e);
            return Collections.emptyList();
        }
    }
}
