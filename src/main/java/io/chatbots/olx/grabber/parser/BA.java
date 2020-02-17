package io.chatbots.olx.grabber.parser;

import io.chatbots.olx.grabber.Offer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class BA extends BaseParser implements Parser {
    @Override
    public List<Offer> parse(String url) {
        try {

            if (url.contains("?")) {
                url = url + "&";
            } else {
                url = url + "?";
            }
            url = url + "sort_order=desc&sort_po=datum";

            return Jsoup.connect(url).get().body()
                    .getElementById("rezultatipretrage")
                    .select(".listitem")
                    .stream()
                    .map(it -> {
                                String link = it.select("a").attr("href");
                                String content = "";
                                return Offer.builder()
                                        .url(cleanUrlFromQueryParams(link))
                                        .content(content)
                                        .name(it.text())
                                        .build();
                            }
                    )
                    .filter(it -> StringUtils.isNotBlank(it.getUrl()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Error while parsing url:" + url, e);
            return Collections.emptyList();
        }
    }
}

