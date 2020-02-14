package io.chatbots.olx.grabber

import io.chatbots.olx.grabber.parser.BA
import io.chatbots.olx.grabber.parser.BR
import io.chatbots.olx.grabber.parser.Future
import io.chatbots.olx.grabber.parser.Parser
import io.chatbots.olx.grabber.parser.QA
import io.chatbots.olx.grabber.parser.Widespread
import spock.lang.Specification

class OlxGrabberImplTest extends Specification {

    def parsers = new HashMap<String, Parser>() {
        {
            put("olx.ua", new Widespread())
            put("olx.ba", new BA())
            put("olx.bg", new Widespread())
            put("olx.pl", new Widespread())
            put("olx.ro", new Widespread())
            put("olx.pt", new Widespread())
            put("dubizzle.com", new Widespread())
            put("olx.com.eg", new QA())
            put("olx.qa", new QA())
            put("olx.com.br", new BR())
            put("olx.uz", new Widespread())
            put("olx.kz", new Widespread())
            put("olx.in", new Future())
            put("olx.co.za", new Future())
            put("olx.com.pk", new Future())
            put("olx.co.id", new Future())
            put("olx.com.ar", new Future())
            put("olx.co.cr", new Future())
        }
    }

    def grabber = new OlxGrabberImpl(parsers)

    def "parse olx.ua to adds"() {
        when:
        String olxUrl = "https://www.olx.ua/list/q-iphone/?search%5Bfilter_float_price%3Afrom%5D=100&search%5Bfilter_float_price%3Ato%5D=100000"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.ba"() {
        when:
        String olxUrl = "https://www.olx.ba/pretraga?trazilica=iphone"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.bg"() {
        when:
        String olxUrl = "https://www.olx.bg/ads/q-iphone/?search%5Bdescription%5D=1"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.pl"() {
        when:
        String olxUrl = "https://www.olx.pl/oferty/q-iphone/"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.ro"() {
        when:
        String olxUrl = "https://www.olx.ro/oferte/q-iphone/"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.pt"() {
        when:
        String olxUrl = "https://www.olx.pt/ads/q-iphone/?search%5Bdescription%5D=1"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "dubizzle.com"() {
        when:
        String olxUrl = "https://uae.dubizzle.com/search/?keywords=iphone&is_basic_search_widget=1&is_search=1"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.com.eg"() {
        when:
        String olxUrl = "https://www.olx.com.eg/ads/q-iphone/"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.qa"() {
        when:
        String olxUrl = "https://olx.qa/ads/q-iphone/"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.com.br"() {
        when:
        String olxUrl = "https://www.olx.com.br/brasil?q=iphone"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.uz"() {
        when:
        String olxUrl = "https://www.olx.uz/list/q-iphone/"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.kz"() {
        when:
        String olxUrl = "https://www.olx.kz/list/q-iphone/"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.in"() {
        when:
        String olxUrl = "https://www.olx.in/items/q-iphone"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.co.za"() {
        when:
        String olxUrl = "https://www.olx.co.za/items/q-iphone"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.com.pk"() {
        when:
        String olxUrl = "https://www.olx.com.pk/items/q-iphone"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.co.id"() {
        when:
        String olxUrl = "https://www.olx.co.id/items/q-iphone"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.com.ar"() {
        when:
        String olxUrl = "https://www.olx.com.ar/items/q-iphone"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }

    def "olx.co.cr"() {
        when:
        String olxUrl = "https://www.olx.co.cr/items/q-iphone"
        List<Offer> ads = grabber.getOffers(olxUrl)
        then:
        !ads.empty
    }


}
