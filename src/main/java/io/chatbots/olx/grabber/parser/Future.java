package io.chatbots.olx.grabber.parser;

import io.chatbots.olx.grabber.Offer;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class Future implements Parser {
    @Override
    public List<Offer> parse(String url) {
        try {

            if (url.contains("?")) {
                url = url + "&";
            } else {
                url = url + "?";
            }
            url = url + "sorting=desc-creation";

            val urlStart = url.substring(0, url.lastIndexOf("/"));

            val body = Jsoup.connect(url).get().body();

            return body.select("ul")
                    .select("svg")
                    .stream()
                    .map(it -> findParentLi(it).map(li -> {
                        val link = li.select("a").attr("href");
                        val content = "";
                        val name = li.select("span")
                                .stream()
                                .filter(span -> "itemTitle".equals(span.attr("data-aut-id")))
                                .findFirst()
                                .map(Element::text)
                                .orElse(null);
                        return Offer.builder()
                                .url(urlStart + link)
                                .content(content)
                                .name(name)
                                .build();
                    }).orElse(null)).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Error while parsing url:" + url, e);
            return Collections.emptyList();
        }
    }

    private Optional<Element> findParentLi(Element it) {
        if (it.is("li")) return Optional.of(it);
        val parent = it.parent();
        if (parent == null) return Optional.empty();
        return findParentLi(parent);
    }

}
