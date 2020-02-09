package io.chatbots.olx.storage.impl;

import io.chatbots.olx.storage.ListenerStorage;
import io.chatbots.olx.storage.entity.Listener;
import org.apache.commons.lang3.NotImplementedException;
import org.bson.types.ObjectId;
import org.jongo.Jongo;
import org.jongo.MongoCollection;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class MongoListenerStorage implements ListenerStorage {

    private MongoCollection listeners;

    public MongoListenerStorage(Jongo jongo) {
        listeners = jongo.getCollection("listeners");
        listeners.ensureIndex("{chatId: 1}");
    }

    @Override
    public Listener saveListener(Listener listener) {
        listeners.save(listener);
        return listener;
    }

    @Override
    public List<Listener> getChatListeners(long userId) {
        return StreamSupport.stream(listeners.find("{chatId: #}", userId).as(Listener.class).spliterator(), false).collect(Collectors.toList());
    }

    @Override
    public List<Listener> getAllListeners() {
        return StreamSupport.stream(listeners.find("{}").as(Listener.class).spliterator(), false).collect(Collectors.toList());
    }

    @Override
    public Listener deleteListener(String listenerId, long chatId) {
        ObjectId objectId = new ObjectId(listenerId);
        Listener listener = listeners.findOne(objectId).as(Listener.class);
        if(listener.getChatId() == chatId) {
            listeners.remove(objectId);
        } else {
            throw new SecurityException("Forbidden, chat id:" + chatId + " listener id: " + listenerId);
        }
        return listener;
    }
}
