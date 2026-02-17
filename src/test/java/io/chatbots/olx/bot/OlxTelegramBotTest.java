package io.chatbots.olx.bot;

import io.chatbots.olx.grabber.Offer;
import io.chatbots.olx.grabber.OlxGrabberImpl;
import io.chatbots.olx.grabber.parser.BA;
import io.chatbots.olx.grabber.parser.BR;
import io.chatbots.olx.grabber.parser.Future;
import io.chatbots.olx.grabber.parser.Parser;
import io.chatbots.olx.grabber.parser.QA;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;


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
            put("olx.com.eg", new QA());
            put("olx.qa", new QA());
            put("olx.com.br", new BR());
            put("olx.uz", new QA());
            put("olx.kz", new QA());
            put("olx.in", new Future());
            put("olx.co.za", new Future());
            put("olx.com.pk", new Future());
//            put("olx.co.id", new Future());
            put("olx.com.ar", new Future());
            put("olx.co.cr", new Future());
        }
    };


    OlxGrabberImpl grabber = new OlxGrabberImpl(parsers);

    @Test
    void olx_ua() {
        String olxUrl = "https://www.olx.ua/list/q-iphone/?search%5Bfilter_float_price%3Afrom%5D=100&search%5Bfilter_float_price%3Ato%5D=100000";
        List<Offer> ads = grabber.getOffers(olxUrl);
        assertFalse(ads.isEmpty());
    }

//    @Test
//    void ba() {
//        String olxUrl = "https://www.olx.ba/pretraga?trazilica=iphone";
//        List<Offer> ads = grabber.getOffers(olxUrl);
//        assertFalse(ads.isEmpty());
//    }

    @Test
    void bg() {
        String olxUrl = "https://www.olx.bg/ads/q-iphone/?search%5Bdescription%5D=1";
        List<Offer> ads = grabber.getOffers(olxUrl);
        assertFalse(ads.isEmpty());
    }

    @Test
    void pl() {
        String olxUrl = "https://www.olx.pl/oferty/q-iphone/";
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

//    @Test
//    void dubizzle() {
//        String olxUrl = "https://uae.dubizzle.com/search/?keywords=iphone";
//        List<Offer> ads = grabber.getOffers(olxUrl);
//        assertFalse(ads.isEmpty());
//    }

    //@Test
//    void olx.com.eg"() {
//
//        String olxUrl = "https://www.olx.com.eg/ads/q-iphone/";
//        List<Offer> ads = grabber.getOffers(olxUrl);
//        then:
//        !ads.empty
//    }
//@Test
//    void olx.qa"() {
//
//        String olxUrl = "https://olx.qa/ads/q-iphone/";
//        List<Offer> ads = grabber.getOffers(olxUrl);
//        then:
//        !ads.empty
//    }
    @Test
    void br() {
        String olxUrl = "https://www.olx.com.br/brasil?q=iphone";
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

    @Test
    void in() {
        String olxUrl = "https://www.olx.in/items/q-iphone";
        List<Offer> ads = grabber.getOffers(olxUrl);
        assertFalse(ads.isEmpty());
    }

    //@Test
//    void olx.co.za"() {
//
//        String olxUrl = "https://www.olx.co.za/items/q-iphone"
//        List<Offer> ads = grabber.getOffers(olxUrl);
//        then:
//        !ads.empty
//    }
//@Test
//    void olx.com.pk"() {
//
//        String olxUrl = "https://www.olx.com.pk/items/q-iphone"
//        List<Offer> ads = grabber.getOffers(olxUrl);
//        then:
//        !ads.empty
//    }
//    @Test
//    void co_id() {
//        String olxUrl = "https://www.olx.co.id/items/q-iphone";
//        List<Offer> ads = grabber.getOffers(olxUrl);
//        assertFalse(ads.isEmpty());
//    }

//    @Test
//    void ar() {
//        String olxUrl = "https://www.olx.com.ar/items/q-iphone";
//        List<Offer> ads = grabber.getOffers(olxUrl);
//        assertFalse(ads.isEmpty());
//    }
//@Test
//    void olx.co.cr"() {
//
//        String olxUrl = "https://www.olx.co.cr/items/q-iphone"
//        List<Offer> ads = grabber.getOffers(olxUrl);
//        then:
//        !ads.empty
//    }


}

