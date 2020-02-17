package io.chatbots.olx.grabber.parser;

import io.chatbots.olx.grabber.Offer;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.jsoup.Jsoup;
import org.jsoup.helper.StringUtil;
import org.jsoup.nodes.Element;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class Widespread extends BaseParser implements Parser {
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
                                String link = extractLink(it);
                                String content = "";
                                String name;
                                name = it.parent().parent()
                                        .select("strong")
                                        .text();
                                return Offer.builder()
                                        .url(cleanUrlFromQueryParams(link))
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

    private String extractLink(Element it) {
        String link = getHref(it);
        if (StringUtil.isBlank(link)) {
            link = it.select("a").attr("href");
        }
        return link;
    }

    private String getHref(Element it) {
        return it.attr("href");
    }
}
