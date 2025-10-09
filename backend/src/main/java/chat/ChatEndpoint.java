package chat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint("/ws")
public class ChatEndpoint {

    private static final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> userPasswords = new ConcurrentHashMap<>();
    private static final Map<String, String> userColors = new ConcurrentHashMap<>();
    private static final List<ChatMessage> messageHistory = Collections.synchronizedList(new ArrayList<>());
    private static final Gson gson = new Gson();
    private static final Map<Session, String> sessionToUser = new ConcurrentHashMap<>();
    private static final Map<String, String> persistentUserIds = new ConcurrentHashMap<>(); // sessionId -> userId
    private static final Map<Session, Integer> lastSeenIndex = new ConcurrentHashMap<>();
    private static final Map<String, Integer> lastSeenIndexByUser = new ConcurrentHashMap<>();

    private static class ChatMessage {
        String id;
        String ownerId;
        String ownerName;
        String encryptedContent;
        String timestamp;

        ChatMessage(String id, String ownerId, String ownerName, String encryptedContent, String timestamp) {
            this.id = id;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.encryptedContent = encryptedContent;
            this.timestamp = timestamp;
        }
    }

    private void sendMessageHistory(Session session, int startIndex) throws IOException {
        synchronized (messageHistory) {
            for (int i = startIndex; i < messageHistory.size(); i++) {
                ChatMessage m = messageHistory.get(i);
                JsonObject payload = new JsonObject();
                payload.addProperty("type", "message");
                payload.addProperty("id", m.id);
                payload.addProperty("ownerId", m.ownerId);
                payload.addProperty("ownerName", m.ownerName);
                payload.addProperty("encryptedContent", m.encryptedContent);
                payload.addProperty("timestamp", m.timestamp);
                payload.addProperty("userColor", userColors.getOrDefault(m.ownerId, "black"));
                session.getBasicRemote().sendText(gson.toJson(payload));
            }
        }
    }

    private int getMessageIndexById(String id) {
        synchronized (messageHistory) {
            for (int i = 0; i < messageHistory.size(); i++) {
                if (messageHistory.get(i).id.equals(id))
                    return i + 1;
            }
        }
        return 0;
    }

    @OnOpen
    public void onOpen(Session session) throws IOException {
        sessions.add(session);

        // restore persistent userId if exists
        if (persistentUserIds.containsKey(session.getId())) {
            String userId = persistentUserIds.get(session.getId());
            sessionToUser.put(session, userId);
        }

        lastSeenIndex.put(session, messageHistory.size()); // by default skip old messages
    }

    @OnClose
    public void onClose(Session session) {
        sessions.remove(session);
        // persist userId even after disconnect
        if (sessionToUser.containsKey(session)) {
            persistentUserIds.put(session.getId(), sessionToUser.get(session));
        }
        sessionToUser.remove(session);
        lastSeenIndex.remove(session);
    }

    @OnMessage
    public void onMessage(String raw, Session sender) throws IOException {
        JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
        String type = obj.has("type") ? obj.get("type").getAsString() : "";

        switch (type) {
            case "set_password": {
                String userId = obj.get("userId").getAsString();
                sessionToUser.put(sender, userId);
                persistentUserIds.put(sender.getId(), userId);

                if (obj.has("userColor")) {
                    userColors.put(userId, obj.get("userColor").getAsString());
                }
                if (obj.has("password")) {
                    userPasswords.put(userId, obj.get("password").getAsString());
                }

                // get new messages for first login
                int lastIndex = obj.has("lastMessageId")
                        ? getMessageIndexById(obj.get("lastMessageId").getAsString())
                        : lastSeenIndexByUser.getOrDefault(userId, 0);

                // update lastSeenIndex for this userId
                lastSeenIndexByUser.put(userId, messageHistory.size());

                sendMessageHistory(sender, lastIndex);
                break;
            }

            case "send_message": {
                String userId = obj.get("userId").getAsString();
                String userName = obj.get("userName").getAsString();
                String userColor = obj.get("userColor").getAsString();
                String content = obj.get("content").getAsString();
                String password = userPasswords.get(userId);
                if (password == null) {
                    JsonObject err = new JsonObject();
                    err.addProperty("type", "error");
                    err.addProperty("message", "Password not set for user");
                    sender.getBasicRemote().sendText(gson.toJson(err));
                    break;
                }

                String output = String.format("\033[1m|%-3s| (Password: %-1s) : %-10s", userName, password, content);
                System.out.println(output);
                
                String encrypted = encryptWithRotatingDigits(content, password);
                String id = UUID.randomUUID().toString();
                String ts = Instant.now().toString();
                ChatMessage m = new ChatMessage(id, userId, userName, encrypted, ts);
                synchronized (messageHistory) {
                    messageHistory.add(m);
                }

                JsonObject payload = new JsonObject();
                payload.addProperty("type", "message");
                payload.addProperty("id", id);
                payload.addProperty("ownerId", userId);
                payload.addProperty("ownerName", userName);
                payload.addProperty("encryptedContent", encrypted);
                payload.addProperty("timestamp", ts);
                payload.addProperty("userColor", userColor);

                broadcast(gson.toJson(payload));
                break;
            }

            case "attempt_unlock": {
                String requesterId = obj.get("requesterId").getAsString();
                String messageId = obj.get("messageId").getAsString();
                String guess = obj.get("guess").getAsString();

                ChatMessage target = null;
                synchronized (messageHistory) {
                    for (ChatMessage m : messageHistory) {
                        if (m.id.equals(messageId)) {
                            target = m;
                            break;
                        }
                    }
                }

                JsonObject reply = new JsonObject();
                reply.addProperty("type", "unlock_result");
                reply.addProperty("messageId", messageId);

                if (target == null) {
                    reply.addProperty("success", false);
                    reply.addProperty("error", "message not found");
                    sendToRequester(requesterId, gson.toJson(reply));
                    break;
                }

                String ownerPassword = userPasswords.get(target.ownerId);
                if (ownerPassword == null) {
                    reply.addProperty("success", false);
                    reply.addProperty("error", "owner password not set");
                    sendToRequester(requesterId, gson.toJson(reply));
                    break;
                }

                if (ownerPassword.equals(guess)) {
                    String decrypted = decryptWithRotatingDigits(target.encryptedContent, ownerPassword);
                    reply.addProperty("success", true);
                    reply.addProperty("decryptedContent", decrypted);
                    reply.addProperty("ownerName", target.ownerName);
                } else {
                    String attempt = decryptWithRotatingDigits(target.encryptedContent, guess);
                    reply.addProperty("success", false);
                    reply.addProperty("decryptedContent", attempt);
                    reply.addProperty("error", "wrong password");
                }

                sendToRequester(requesterId, gson.toJson(reply));
                break;
            }
        }
    }

    private static void broadcast(String text) {
        synchronized (messageHistory) {
            for (Session s : sessions) {
                String userId = sessionToUser.get(s);
                if (userId == null)
                    continue;

                int startIndex = lastSeenIndexByUser.getOrDefault(userId, 0);
                for (int i = startIndex; i < messageHistory.size(); i++) {
                    ChatMessage m = messageHistory.get(i);
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "message");
                    payload.addProperty("id", m.id);
                    payload.addProperty("ownerId", m.ownerId);
                    payload.addProperty("ownerName", m.ownerName);
                    payload.addProperty("encryptedContent", m.encryptedContent);
                    payload.addProperty("timestamp", m.timestamp);
                    payload.addProperty("userColor", userColors.getOrDefault(m.ownerId, "black"));
                    try {
                        s.getBasicRemote().sendText(gson.toJson(payload));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                lastSeenIndexByUser.put(userId, messageHistory.size());
            }
        }
    }

    private static void sendToRequester(String requesterId, String jsonText) {
        Session targetSession = null;
        for (Map.Entry<Session, String> entry : sessionToUser.entrySet()) {
            if (entry.getValue().equals(requesterId)) {
                targetSession = entry.getKey();
                break;
            }
        }
        if (targetSession != null && targetSession.isOpen()) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "internal_delivery");
            wrapper.addProperty("requesterId", requesterId);
            wrapper.addProperty("payload", jsonText);
            try {
                targetSession.getBasicRemote().sendText(gson.toJson(wrapper));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static String encryptWithRotatingDigits(String text, String password) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit = Character.getNumericValue(password.charAt(i % password.length()));
            out.append(shiftChar(c, digit));
        }
        return out.toString();
    }

    private static String decryptWithRotatingDigits(String text, String password) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit = Character.getNumericValue(password.charAt(i % password.length()));
            out.append(shiftChar(c, 26 - (digit % 26)));
        }
        return out.toString();
    }

    private static char shiftChar(char c, int shift) {
        if (c >= 'a' && c <= 'z')
            return (char) ('a' + (c - 'a' + shift) % 26);
        if (c >= 'A' && c <= 'Z')
            return (char) ('A' + (c - 'A' + shift) % 26);
        return c;
    }
}
