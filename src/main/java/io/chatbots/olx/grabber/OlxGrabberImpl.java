package io.chatbots.olx.grabber;

import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class OlxGrabberImpl implements OlxGrabber {
    @Override
    public List<Offer> getOffers(String url) {
        try {

            if (url.contains("?")) {
                url = url + "&";
            } else {
                url = url + "?";
            }
            url = url + "search%5Border%5D=created_at%3Adesc";

            return Jsoup.connect(url).get().body()
                    .getElementById("offers_table")
                    .select(".thumb")
                    .stream()
                    .map(it -> {
                                String link = it.attr("href");
                                String content = "";
                                String name;
                                name = it.select("img").attr("alt");
                                it.parent().parent()
                                        .select("strong")
                                        .text();
//                                    Document document = Jsoup.connect(link).get();
//                                    content = document.getElementById("textContent").text();
//                                    name = document.getElementsByClass("offer-titlebox").select("h1").text();
//                                } catch (IOException e) {
//                                    e.printStackTrace();
//                                }
                                return Offer.builder()
                                        .url(link)
                                        .content(content)
                                        .name(name)
                                        .build();
                            }
                    ).collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
}
