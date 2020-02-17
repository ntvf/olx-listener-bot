package io.chatbots.olx.checker;

import io.chatbots.olx.grabber.OlxGrabber;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;

@Slf4j
public class RegressionChecker {
    private final OlxGrabber olxGrabber;

    public RegressionChecker(OlxGrabber grabber) {
        this.olxGrabber = grabber;
    }

    public void checkSitesForRegression() {
        val urlsForCheck = Arrays.asList(
                "https://www.olx.ua/list/q-iphone/?search%5Bfilter_float_price%3Afrom%5D=100&search%5Bfilter_float_price%3Ato%5D=100000",
                "https://www.olx.ba/pretraga?trazilica=iphone",
                "https://www.olx.bg/ads/q-iphone/?search%5Bdescription%5D=1",
                "https://www.olx.pl/oferty/q-iphone/",
                "https://www.olx.ro/oferte/q-iphone/",
                "https://www.olx.pt/ads/q-iphone/?search%5Bdescription%5D=1",
                "https://uae.dubizzle.com/search/?keywords=iphone&is_basic_search_widget=1&is_search=1",
                "https://www.olx.com.eg/ads/q-iphone/",
                "https://olx.qa/ads/q-iphone/",
                "https://www.olx.com.br/brasil?q=iphone",
                "https://www.olx.uz/list/q-iphone/",
                "https://www.olx.kz/list/q-iphone/",
                "https://www.olx.in/items/q-iphone",
                "https://www.olx.co.za/items/q-iphone",
                "https://www.olx.com.pk/items/q-iphone",
                "https://www.olx.co.id/items/q-iphone",
                "https://www.olx.com.ar/items/q-iphone",
                "https://www.olx.co.cr/items/q-iphone");

        urlsForCheck.stream().forEach(url -> {
            olxGrabber.getOffers(url).forEach(offer -> {
                if (StringUtils.isBlank(offer.getUrl())) {
                    log.warn("!!! URL IS NOT PARSING WELL:{}", url);
                }
            });
        });

    }
}
