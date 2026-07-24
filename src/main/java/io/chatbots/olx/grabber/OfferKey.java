package io.chatbots.olx.grabber;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stable dedup key for an OLX offer.
 *
 * <p>Both OLX and Otodom listing URLs embed an editable, human-readable slug before the immutable
 * ad id: {@code .../kawalerka-na-zoliborzu-CID3-ID1bA1FD.html} (OLX) and
 * {@code .../mieszkanie-na-woli-ID4Cm5b} (Otodom, no {@code .html} suffix). When a seller edits the
 * title the slug changes but the {@code -ID<token>} stays, so hashing the whole URL makes an edited
 * listing look brand-new and re-posts it. Keying on the ad id alone avoids that.
 *
 * <p>URLs without a recognised {@code -ID<token>} segment fall back to the full URL, preserving
 * previous behaviour.
 */
public final class OfferKey {

    private static final Pattern OLX_AD_ID = Pattern.compile("-ID([0-9A-Za-z]+)");

    private OfferKey() {
    }

    public static String of(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = OLX_AD_ID.matcher(url);
        return m.find() ? m.group(1) : url;
    }
}
