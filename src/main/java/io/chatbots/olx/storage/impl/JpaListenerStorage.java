package io.chatbots.olx.storage.impl;

import io.chatbots.olx.storage.ListenerJpaRepository;
import io.chatbots.olx.storage.ListenerOfferHashRepository;
import io.chatbots.olx.storage.ListenerStorage;
import io.chatbots.olx.storage.entity.Listener;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@RequiredArgsConstructor
@Transactional
public class JpaListenerStorage implements ListenerStorage {

    private final ListenerJpaRepository repo;
    private final ListenerOfferHashRepository hashRepository;

    @Override
    public Listener saveListener(Listener listener) {
        listener.setUpdated(new Date());
        return repo.save(listener);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Listener> getChatListeners(long chatId) {
        return repo.findByChatIdAndActiveTrue(chatId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Listener> getAllListeners() {
        return repo.findByActiveTrue();
    }

    @Override
    public void deactivateChatListeners(long chatId) {
        repo.deactivateByChatId(chatId);
    }

    @Override
    public Listener deleteListener(long listenerId, long chatId) {
        Listener listener = repo.findById(listenerId)
                .orElseThrow(() -> new IllegalArgumentException("Listener not found: " + listenerId));
        if (listener.getChatId() != chatId) {
            throw new SecurityException("Forbidden, chat id:" + chatId + " listener id: " + listenerId);
        }
        hashRepository.deleteByListenerId(listenerId);
        listener.setActive(false);
        return repo.save(listener);
    }
}
