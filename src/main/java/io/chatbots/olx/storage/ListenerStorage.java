package io.chatbots.olx.storage;

import io.chatbots.olx.storage.entity.Listener;

import java.util.List;

public interface ListenerStorage {
    Listener saveListener(Listener listener);
    List<Listener> getChatListeners(long userId);
    List<Listener> getAllListeners();
    Listener deleteListener(String listenerId, long chatId);
}
