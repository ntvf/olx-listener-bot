package io.chatbots.olx.grabber.parser.bazaraki;

import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.parser.BaseParser;
import io.chatbots.olx.grabber.parser.Parser;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
public class BazarakiParser extends BaseParser implements Parser {

    @Override
    public List<Offer> parse(String url) {
        val offers = new ArrayList<Offer>();
        try {
            URL parsedUrl = new URL(url);
            val overriddenOrdering = "ordering=newest";
            val defaultOrderingOptional = Arrays.asList(Optional.ofNullable(parsedUrl.getQuery()).orElse("").split("&")).stream().filter(it -> it.contains("ordering")).findFirst();
            if (defaultOrderingOptional.isPresent()) {
                val defaultOrdering = defaultOrderingOptional.get();
                url = url.replace(defaultOrdering, overriddenOrdering);
            } else {
                val symbol = StringUtils.isBlank(parsedUrl.getQuery()) ? "?" : "&";
                url = url + symbol + overriddenOrdering;
            }
            val host = parsedUrl.getHost();
            val body = Jsoup.connect(url).get().body();

            val blocks = body.getElementsByClass("announcement-container");

            blocks.stream()
                    .filter(it -> it.hasAttr("data-t-regular"))
                    .forEach(block -> {
                        val title = block.getElementsByClass("announcement-block__title");
                        val content = title.text()
                                + " " + block.getElementsByClass("announcement-block__description").text()
                                + " " + block.getElementsByClass("announcement-block__price").text();
                        offers.add(Offer.builder()
                                .name(title.text())
                                .content(content)
                                .url(host + title.attr("href"))
                                .build());
                    });
        } catch (Exception e) {
            log.warn("Exception while processing url:{}", url, e);
            return Collections.emptyList();
        }

        return offers;
    }
}
