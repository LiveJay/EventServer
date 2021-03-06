package com.universeprojects.eventserver;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

public class UserService {
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final ReentrantLock userLock = new ReentrantLock();
    private final EventServerVerticle verticle;

    public UserService(EventServerVerticle verticle) {
        this.verticle = verticle;
    }

    public User getOrCreateUser(String userId) {
        userLock.lock();
        try {
            final User existingUser = users.get(userId);
            if(existingUser != null) {
                return existingUser;
            }
            final User newUser = new User(userId);
            newUser.executeLocked((user) -> {
                user.updateConsumer = verticle.eventBus.<JsonArray>consumer(
                    verticle.generateUserUpdateAddress(userId),
                    (channels) ->
                        processUpdateChannels(user, channels)
                );
                user.privateMessageConsumer = verticle.eventBus.<ChatMessage>consumer(
                    verticle.generatePrivateMessageAddress(userId),
                    (message) ->
                        processPrivateMessage(user, message)
                );
            });
            users.put(userId, newUser);
            return newUser;
        } finally {
            userLock.unlock();
        }
    }

    public boolean checkAndRemoveUser(User user, BooleanSupplier supplier) {
        userLock.lock();
        try {
            return user.executeLockedReturning((u) -> {
                boolean check = supplier.getAsBoolean();
                if (!check) {
                    return false;
                }
                verticle.channelService.updateSubscriptions(u, Collections.emptyList());
                users.remove(user.userId);
                user.remove();
                return true;
            });
        } finally {
            userLock.unlock();
        }
    }

    private void processUpdateChannels(User user, Message<JsonArray> message) {
        final Set<String> channels = new LinkedHashSet<>();
        for (final Object channelObj : message.body()) {
            channels.add((String) channelObj);
        }
        user.executeLocked(u -> verticle.channelService.updateSubscriptions(u, channels));
    }

    private void processPrivateMessage(User user, Message<ChatMessage> message) {
        final JsonObject json = ChatEnvelope.forMessage(message.body()).toJson();
        final Buffer buffer = json.toBuffer();
        user.sockets.forEach(socket -> socket.write(buffer));
    }

    //new userId - no user - create user-object - add channel-subscription / add user to channel-subscription
    //existing userId - get user -> add socket to user
    //user changes userId -> ?
}
