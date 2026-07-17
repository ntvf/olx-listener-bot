package io.chatbots.olx.channel;

import io.chatbots.olx.channel.entity.Channel;
import io.chatbots.olx.channel.entity.ChannelFeed;
import io.chatbots.olx.grabber.OlxGrabber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberUpdated;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Admin-only channel management: channels register themselves when the bot is promoted
 * to channel admin ({@code my_chat_member}); feeds are linked with hidden DM commands.
 */
@Slf4j
@RequiredArgsConstructor
public class ChannelAdminService {

    private final ChannelRepository channelRepository;
    private final ChannelFeedRepository feedRepository;
    private final OlxGrabber grabber;

    public void handleChatMemberUpdate(ChatMemberUpdated event) {
        if (!"channel".equals(event.getChat().getType())) return;
        String status = event.getNewChatMember().getStatus();
        long chatId = event.getChat().getId();
        if ("administrator".equals(status)) {
            Channel channel = channelRepository.findById(chatId).orElse(Channel.builder()
                    .chatId(chatId)
                    .addedAt(Instant.now())
                    .build());
            channel.setTitle(event.getChat().getTitle());
            channel.setUsername(event.getChat().getUserName());
            channelRepository.save(channel);
            log.info("Registered channel '{}' chat_id={}", event.getChat().getTitle(), chatId);
        } else if ("left".equals(status) || "kicked".equals(status)) {
            List<ChannelFeed> feeds = feedRepository.findByChannelChatId(chatId);
            feeds.forEach(f -> f.setActive(false));
            feedRepository.saveAll(feeds);
            log.info("Bot removed from channel chat_id={}, deactivated {} feeds", chatId, feeds.size());
        }
    }

    /** @return reply text, or null when the message is not a channel-admin command */
    public String handleCommand(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("\\s+");
        return switch (parts[0]) {
            case "/channels" -> listChannels();
            case "/link" -> link(parts);
            case "/unlink" -> unlink(parts);
            default -> null;
        };
    }

    private String listChannels() {
        List<Channel> channels = channelRepository.findAll();
        if (channels.isEmpty()) {
            return "No channels yet. Add the bot as admin to a channel first.";
        }
        StringBuilder sb = new StringBuilder();
        for (Channel channel : channels) {
            sb.append(channel.getChatId());
            if (channel.getUsername() != null) sb.append(" @").append(channel.getUsername());
            if (channel.getTitle() != null) sb.append(" — ").append(channel.getTitle());
            sb.append('\n');
            for (ChannelFeed feed : feedRepository.findByChannelChatId(channel.getChatId())) {
                sb.append("  [").append(feed.getId()).append(feed.isActive() ? "" : ", inactive")
                        .append(feed.getLabel() == null ? "" : ", #" + feed.getLabel())
                        .append("] ").append(feed.getFeedUrl()).append('\n');
            }
        }
        sb.append("\n/link <chat_id|@username|title> <search-url> [label]\n/unlink <feed_id>");
        return sb.toString();
    }

    private String link(String[] parts) {
        if (parts.length < 3) return "Usage: /link <chat_id|@username|title> <search-url> [label]";
        Optional<Channel> channel = resolveChannel(parts[1]);
        if (channel.isEmpty()) return "Channel not found: " + parts[1] + "\nUse /channels to list known channels.";
        String url = parts[2];
        if (!grabber.supportsUrl(url)) return "Unsupported search URL: " + url;
        String label = parts.length > 3 ? parts[3] : null;

        ChannelFeed feed = feedRepository.save(ChannelFeed.builder()
                .channelChatId(channel.get().getChatId())
                .feedUrl(url)
                .label(label)
                .active(true)
                .createdAt(Instant.now())
                .build());
        return "Linked feed " + feed.getId() + " to channel " + channel.get().getChatId()
                + (label == null ? "" : " (#" + label + ")")
                + "\nFirst poll seeds price history silently; posting starts with the next new listings.";
    }

    private String unlink(String[] parts) {
        if (parts.length < 2) return "Usage: /unlink <feed_id>";
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
                    return "Feed " + feedId + " deactivated.";
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
