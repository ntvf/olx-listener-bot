package io.chatbots.olx.grabber;

import java.util.List;

public interface OlxGrabber {
    List<Offer> getOffers(String url);
}
