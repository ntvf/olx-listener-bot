package io.chatbots.olx.grabber.parser;

import io.chatbots.olx.grabber.Offer;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.jsoup.Jsoup;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class Widespread implements Parser {
    @Override
    public List<Offer> parse(String url) {
        try {

            if (url.contains("?")) {
                url = url + "&";
            } else {
                url = url + "?";
            }
            url = url + "sf=1";

            val body = Jsoup.connect(url).get().body();

            return Optional.ofNullable(body.getElementById("offers_table"))
                    .orElseGet(() -> body.getElementById("results-list"))
                    .select(".thumb")
                    .stream()
                    .map(it -> {
                                String link = it.attr("href");
                                String content = "";
                                String name;
                                name = it.select("img").attr("alt");
                                it.parent().parent()
                                        .select("strong")
                                        .text();
                                return Offer.builder()
                                        .url(link)
                                        .content(content)
                                        .name(name)
                                        .build();
                            }
                    ).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Error while parsing url:" + url, e);
            return Collections.emptyList();
        }
    }
}
