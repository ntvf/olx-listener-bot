package io.chatbots.olx.grabber

import spock.lang.Specification

class OlxGrabberImplTest extends Specification {

    def "parse olx to adds"() {
        when:
        String olxUrl = "https://www.olx.ua/list/q-iphone/?search%5Bfilter_float_price%3Afrom%5D=100&search%5Bfilter_float_price%3Ato%5D=100000"
        List<Offer> ads = new OlxGrabberImpl().getOffers(olxUrl)
        then:
        !ads.empty
    }
}
