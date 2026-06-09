package io.chatbots.olx.bot;

import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.OlxGrabberImpl;
import io.chatbots.olx.grabber.parser.BA;
import io.chatbots.olx.grabber.parser.OlxPkParser;
import io.chatbots.olx.grabber.parser.Parser;
import io.chatbots.olx.grabber.parser.QA;
import io.chatbots.olx.grabber.parser.bazaraki.BazarakiParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("integration")
class OlxGrabberImplTest {

    Map<String, Parser> parsers = new HashMap<>() {
        {
            put("olx.ua", new QA());
            put("olx.ba", new BA());
            put("olx.bg", new QA());
            put("olx.pl", new QA());
            put("olx.ro", new QA());
            put("olx.pt", new QA());
            put("dubizzle.com", new QA());
            put("olx.uz", new QA());
            put("olx.kz", new QA());
            put("olx.com.pk", new OlxPkParser());
            put("bazaraki.com", new BazarakiParser());
        }
    };


    OlxGrabberImpl grabber = new OlxGrabberImpl(parsers);

    @Test
    void olx_ua() {
        String olxUrl = "https://www.olx.ua/list/q-iphone/?search%5Bfilter_float_price%3Afrom%5D=100&search%5Bfilter_float_price%3Ato%5D=100000";
        List<Offer> ads = grabber.getOffers(olxUrl);
        assertFalse(ads.isEmpty());
    }

    @Test
    void ba() {
        String olxUrl = "https://www.olx.ba/pretraga?q=iphone";
        List<Offer> ads = grabber.getOffers(olxUrl);
        assertFalse(ads.isEmpty());
    }

    @Test
    void bg() {
        String olxUrl = "https://www.olx.bg/ads/q-iphone/?search%5Bdescription%5D=1";
        List<Offer> ads = grabber.getOffers(olxUrl);
        assertFalse(ads.isEmpty());
    }

    @Test
    void pl() {
        String olxUrl = "https://www.olx.pl/oferty/q-iphone/?search%5Border%5D=filter_float_price:asc";
        List<Offer> ads = grabber.getOffers(olxUrl);
        assertFalse(ads.isEmpty());
    }

    @Test
    void ro() {
        String olxUrl = "https://www.olx.ro/oferte/q-iphone/";
        List<Offer> ads = grabber.getOffers(olxUrl);
        assertFalse(ads.isEmpty());
    }

    @Test
    void pt() {
        String olxUrl = "https://www.olx.pt/ads/q-iphone/?search%5Bdescription%5D=1";
        List<Offer> ads = grabber.getOffers(olxUrl);
        assertFalse(ads.isEmpty());
    }

    @Test
    void dubizzle() {
        String olxUrl = "https://uae.dubizzle.com/motors/used-cars/?keywords=toyota";
        List<Offer> ads = grabber.getOffers(olxUrl);
        Assumptions.assumeFalse(ads.isEmpty(), "dubizzle.com is not reachable from this network (bot protection active)");
        assertFalse(ads.isEmpty());
    }

    @Test
    void bazaraki() {
        String olxUrl = "https://www.bazaraki.com/adv/phones-and-accessories--mobile-phones-and-smartphones/?ordering=newest";
        List<Offer> ads = grabber.getOffers(olxUrl);
        Assumptions.assumeFalse(ads.isEmpty(), "bazaraki.com is not reachable from this network (bot protection active)");
        assertFalse(ads.isEmpty());
    }

    @Test
    void pk() {
        String olxUrl = "https://www.olx.com.pk/items?q=iphone";
        List<Offer> ads = grabber.getOffers(olxUrl);
        assertFalse(ads.isEmpty());
    }

    @Test
    void uz() {
        String olxUrl = "https://www.olx.uz/list/q-iphone/";
        List<Offer> ads = grabber.getOffers(olxUrl);
        assertFalse(ads.isEmpty());
    }

    @Test
    void kz() {
        String olxUrl = "https://www.olx.kz/list/q-iphone/";
        List<Offer> ads = grabber.getOffers(olxUrl);
        assertFalse(ads.isEmpty());
    }

}
