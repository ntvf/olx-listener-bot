package io.chatbots.olx.grabber.parser;

import io.chatbots.olx.grabber.Offer;

import java.util.List;

public interface Parser {
    List<Offer> parse(String url);
}
