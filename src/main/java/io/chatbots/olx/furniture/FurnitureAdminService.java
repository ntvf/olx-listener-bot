package io.chatbots.olx.furniture;

import io.chatbots.olx.channel.ChannelRepository;
import io.chatbots.olx.channel.entity.Channel;
import io.chatbots.olx.furniture.entity.FurnitureFeed;
import io.chatbots.olx.grabber.OlxGrabber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Admin-only management of the used-IKEA deal channel, mirroring {@code ChannelAdminService} but on
 * the isolated furniture_feeds table. Channels register themselves through the shared rental flow
 * (bot promoted to channel admin); here the operator links one model search per feed.
 */
@Slf4j
@RequiredArgsConstructor
public class FurnitureAdminService {

    private final ChannelRepository channelRepository;
    private final FurnitureFeedRepository feedRepository;
    private final OlxGrabber grabber;

    /** @return reply text, or null when the message is not a furniture-admin command */
    public String handleCommand(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("\\s+");
        return switch (parts[0]) {
            case "/ikea" -> listFeeds();
            case "/ikea-link" -> link(parts);
            case "/ikea-unlink" -> unlink(parts);
            default -> null;
        };
    }

    private String listFeeds() {
        List<FurnitureFeed> feeds = feedRepository.findAll();
        if (feeds.isEmpty()) {
            return "No furniture feeds yet.\n/ikea-link <chat_id|@username|title> <search-url> [label]"
                    + "\n(link one broad Warsaw q=ikea search; models are detected in code)";
        }
        StringBuilder sb = new StringBuilder("IKEA feeds:\n");
        for (FurnitureFeed feed : feeds) {
            sb.append("  [").append(feed.getId()).append(feed.isActive() ? "" : ", inactive")
                    .append(feed.getLabel() == null ? "" : ", #" + feed.getLabel())
                    .append("] chat=").append(feed.getChannelChatId())
                    .append(' ').append(feed.getFeedUrl()).append('\n');
        }
        sb.append("\n/ikea-link <chat_id|@username|title> <search-url> [label]\n/ikea-unlink <feed_id>");
        return sb.toString();
    }

    private String link(String[] parts) {
        if (parts.length < 3) return "Usage: /ikea-link <chat_id|@username|title> <search-url> [label]";
        Optional<Channel> channel = resolveChannel(parts[1]);
        if (channel.isEmpty()) return "Channel not found: " + parts[1] + "\nUse /channels to list known channels.";
        String url = parts[2];
        if (!grabber.supportsUrl(url)) return "Unsupported search URL: " + url;
        String label = parts.length > 3 ? parts[3] : null;

        FurnitureFeed feed = feedRepository.save(FurnitureFeed.builder()
                .channelChatId(channel.get().getChatId())
                .feedUrl(url)
                .label(label)
                .active(true)
                .createdAt(Instant.now())
                .build());
        return "Linked IKEA feed " + feed.getId() + " to channel " + channel.get().getChatId()
                + "\nFirst poll seeds the model medians silently; deals post from the next new listings.";
    }

    private String unlink(String[] parts) {
        if (parts.length < 2) return "Usage: /ikea-unlink <feed_id>";
        long feedId;
        try {
            feedId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return "Not a feed id: " + parts[1];
        }
        return feedRepository.findById(feedId)
                .map(feed -> {
                    feed.setActive(false);
                    feedRepository.save(feed);
                    return "IKEA feed " + feedId + " deactivated.";
                })
                .orElse("Feed not found: " + feedId);
    }

    private Optional<Channel> resolveChannel(String target) {
        if (target.startsWith("@")) {
            return channelRepository.findByUsernameIgnoreCase(target.substring(1));
        }
        try {
            return channelRepository.findById(Long.parseLong(target));
        } catch (NumberFormatException ignored) {
            return channelRepository.findByTitleIgnoreCase(target);
        }
    }
}
