package com.universeprojects.eventserver;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.MultiMap;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.Lock;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class SlackCommunicationService {
    public static final String CONFIG_SLACK_ENABLED = "slack.enabled";
    public static final String CONFIG_SLACK_URL = "slack.url";
    public static final String CONFIG_SLACK_CHANNELS_INCOMING = "slack.channels.incoming";
    public static final String CONFIG_SLACK_CHANNELS_OUTGOING = "slack.channels.outgoing";
    public static final String CONFIG_SLACK_USERNAME = "slack.username";
    public static final String CONFIG_SLACK_TOKEN = "slack.token";
    public static final int FAILOVER_CHECK_TIME = 60 * 1000;
    public static final String DATA_MARKER_FROM_SLACK = "__fromSlack";
    public static final String DATA_AUTHOR_LINK = "slackAuthorLink";
    public static final String DATA_AUTHOR_COLOR = "slackAuthorColor";
    public static final String DATA_ADDITIONAL_FIELDS = "slackAdditionalFields";

    private final EventServerVerticle verticle;
    private final HttpClient httpClient;
    private final boolean slackEnabled;
    private final String slackUrl;
    private final String slackUsername;
    private final String slackToken;
    private final Map<String, String> slackOutgoingChannelMap;
    private final Map<String, String> slackIncomingChannelMap;
    private final Object timerLock = new Object();
    private Lock instanceLock;
    private Long timerId;

    public SlackCommunicationService(EventServerVerticle verticle) {
        this.verticle = verticle;
        this.slackEnabled = Config.getBoolean(CONFIG_SLACK_ENABLED, false);
        this.slackUrl = Config.getString(CONFIG_SLACK_URL, null);
        this.slackUsername = Config.getString(CONFIG_SLACK_USERNAME, null);
        this.slackToken = Config.getString(CONFIG_SLACK_TOKEN, null);
        String outgoingChannelsStr = Config.getString(CONFIG_SLACK_CHANNELS_OUTGOING, null);
        if(outgoingChannelsStr != null) {
            Map<String, String> map = new LinkedHashMap<>();
            JsonObject json = new JsonObject(outgoingChannelsStr);
            for(Map.Entry<String, Object> entry : json.getMap().entrySet()) {
                map.put(entry.getKey(), (String) entry.getValue());
            }
            this.slackOutgoingChannelMap = Collections.unmodifiableMap(map);
        } else {
            this.slackOutgoingChannelMap = Collections.emptyMap();
        }
        String incomingChannelsStr = Config.getString(CONFIG_SLACK_CHANNELS_INCOMING, null);
        if(incomingChannelsStr != null) {
            Map<String, String> map = new LinkedHashMap<>();
            JsonObject json = new JsonObject(incomingChannelsStr);
            for(Map.Entry<String, Object> entry : json.getMap().entrySet()) {
                map.put(entry.getKey(), (String) entry.getValue());
            }
            this.slackIncomingChannelMap = Collections.unmodifiableMap(map);
        } else {
            this.slackIncomingChannelMap = Collections.emptyMap();
        }
        this.httpClient = verticle.getVertx().createHttpClient();
    }

    public boolean canActivateOutgoing() {
        return slackEnabled && slackUrl != null && !slackOutgoingChannelMap.isEmpty();
    }

    public boolean canActivateIncoming() {
        return slackEnabled && slackUrl != null && slackToken != null && !slackIncomingChannelMap.isEmpty();
    }

    public void handleIncomingSlack(RoutingContext context) {
        if(context.request().method() != HttpMethod.POST) {
            context.response().setStatusCode(405);
            context.response().end();
            return;
        }
        context.request().endHandler((ignored) -> {
            MultiMap attributes = context.request().formAttributes();
            String token = attributes.get("token");
            String userName = attributes.get("user_name");
            String text = attributes.get("text");
            String slackChannel = attributes.get("channel_name");
            if(token == null || userName == null || text == null || slackChannel == null) {
                context.response().setStatusCode(400);
                context.response().end();
                return;
            }
            if(slackToken.equals(token)) {
                context.response().setStatusCode(403);
                context.response().end();
                return;
            }
            String channel = slackIncomingChannelMap.get(slackChannel);
            if(channel == null) {
                context.response().end();
                return;
            }
            String address = verticle.generateChannelAddress(channel);
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.channel = channel;
            chatMessage.senderDisplayName = userName;
            chatMessage.senderId = "slack:"+slackChannel;
            chatMessage.text = text;
            chatMessage.additionalData = new JsonObject().put(DATA_MARKER_FROM_SLACK, true);
            verticle.eventBus.publish(address, chatMessage);

            context.response().end();
        });
    }

    @SuppressWarnings("unused")
    public boolean isActive() {
        return canActivateOutgoing() && instanceLock != null;
    }

    public void activate() {
        if(!canActivateOutgoing()) return;
        verticle.sharedDataService.getSlackLock((result) -> {
            if(result.succeeded()) {
                instanceLock = result.result();
                cancelTimer();
                setupHandlers();
            } else {
                setupTimer();
            }
        });
    }

    public void setupRoute(Router router) {
        if(canActivateIncoming()) {
            router.route("/slack").handler(this::handleIncomingSlack);
        }
    }

    private void setupTimer() {
        synchronized (timerLock) {
            if (timerId != null) return;
            timerId = verticle.getVertx().setPeriodic(FAILOVER_CHECK_TIME, (ignored) -> activate());
        }
    }

    private void cancelTimer() {
        synchronized (timerLock) {
            if (timerId == null) return;
            verticle.getVertx().cancelTimer(timerId);
            timerId = null;
        }
    }

    private void setupHandlers() {
        for(Map.Entry<String, String> entry : slackOutgoingChannelMap.entrySet()) {
            String channel = entry.getKey();
            String slackChannel = entry.getValue();
            verticle.eventBus.<ChatMessage>consumer(verticle.generateChannelAddress(channel),
                    (message) -> processChannelMessage(message, slackChannel)
            );
        }
    }

    private void processChannelMessage(Message<ChatMessage> message, String slackChannel) {
        ChatMessage chatMessage = message.body();
        if(chatMessage.text == null) {
            return;
        }
        JsonObject additionalData = chatMessage.additionalData;
        String authorLink = null;
        String authorColor = null;
        JsonArray additionalFields = null;
        if(additionalData != null)  {
            Boolean fromSlack = additionalData.getBoolean(DATA_MARKER_FROM_SLACK, false);
            if(fromSlack != null && fromSlack) {
                return;//Prevent loops
            }
            authorLink = additionalData.getString(DATA_AUTHOR_LINK);
            authorColor = additionalData.getString(DATA_AUTHOR_COLOR);
            additionalFields = additionalData.getJsonArray(DATA_ADDITIONAL_FIELDS);
        }
        final HttpClientRequest request = httpClient.post(slackUrl);
        request.putHeader(HttpHeaderNames.CONTENT_TYPE, "application/json");
        JsonObject payload = new JsonObject();
        payload.put("channel", slackChannel);
        putIfNotNull(payload, "username", slackUsername);
        JsonArray attachments = new JsonArray();
        payload.put("attachments", attachments);

        JsonObject attachment = new JsonObject();
        attachments.add(attachment);
        putIfNotNull(attachment, "fallback", chatMessage.text);
        putIfNotNull(attachment, "text", chatMessage.text);
        putIfNotNull(attachment, "author_name", chatMessage.senderDisplayName);
        putIfNotNull(attachment, "author_link", authorLink);
        putIfNotNull(attachment, "color", authorColor);
        if(additionalFields != null) {
            attachment.put("fields", additionalFields.copy());
        }
        request.end(payload.encode());
    }


    private static void putIfNotNull(JsonObject object, String key, String data) {
        if(data != null) {
            object.put(key, data);
        }
    }

}