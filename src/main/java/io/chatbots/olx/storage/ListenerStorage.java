package io.chatbots.olx.storage;

import io.chatbots.olx.storage.entity.Listener;

import java.util.List;

public interface ListenerStorage {
    Listener saveListener(Listener listener);
    List<Listener> getChatListeners(long userId);
    List<Listener> getAllListeners();

    Listener deleteListener(long listenerId, long chatId);

    void deactivateChatListeners(long chatId);
}
