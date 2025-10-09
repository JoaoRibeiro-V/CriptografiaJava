package chat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID; // WebSocket API
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

/**
 * WebSocket chat endpoint.
 * 
 * Cada client connectado representa uma sessão.
 * Lida com envio de mensagens criptografadas e requests pelo usuário
 */
@ServerEndpoint("/ws")
public class ChatEndpoint {

    // -----------------------------
    // ==== SERVER STATE FIELDS ====
    // -----------------------------

    // Todas as conexões de WebSocket que estão presentes
    private static final Set<Session> sessions = ConcurrentHashMap.newKeySet();

    // Guarda a senha de usuário para as mensagens
    private static final Map<String, String> userPasswords = new ConcurrentHashMap<>();

    // Guarda a cor de usuário definida pelo sistema
    private static final Map<String, String> userColors = new ConcurrentHashMap<>();

    // Guarda todas mensagens já enviadas
    private static final List<ChatMessage> messageHistory = Collections.synchronizedList(new ArrayList<>());

    // Manipulador de arquivos JSON
    private static final Gson gson = new Gson();

    // Mapeia as Sessoes de WebSocket
    private static final Map<Session, String> sessionToUser = new ConcurrentHashMap<>();

    // Mantém id de usuario após perda de sessao
    private static final Map<String, String> persistentUserIds = new ConcurrentHashMap<>();

    // Tracks quantas mensagens a sessão do usuário ja viu
    private static final Map<Session, Integer> lastSeenIndex = new ConcurrentHashMap<>();

    // Tracks a ultima mensagem que a sessão do usuário viu
    private static final Map<String, Integer> lastSeenIndexByUser = new ConcurrentHashMap<>();

    // -----------------------------
    // ==== INTERNAL STRUCTURES ====
    // -----------------------------

    /**
     * Classe que representa uma mensagem.
     * É armazenada criptografada, apenas descriptografada para o cliente.
     */
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

    // -----------------------------
    // ==== UTILITY METHODS ====
    // -----------------------------

    /**
     * Envia o histórico de mensagens começando por um index para um client específico
     */
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

    /**
     * Acha a posição de uma mensagem por seu ID
     */
    private int getMessageIndexById(String id) {
        synchronized (messageHistory) {
            for (int i = 0; i < messageHistory.size(); i++) {
                if (messageHistory.get(i).id.equals(id))
                    return i + 1;
            }
        }
        return 0;
    }

    // -----------------------------
    // ==== WEBSOCKET HANDLERS ====
    // -----------------------------

    @OnOpen
    public void onOpen(Session session) throws IOException {
        sessions.add(session);
        // (user reconnect)
        if (persistentUserIds.containsKey(session.getId())) {
            String userId = persistentUserIds.get(session.getId());
            sessionToUser.put(session, userId);
        }

        // Por padrao novos usuários pulam
        lastSeenIndex.put(session, messageHistory.size());
    }

    @OnClose
    public void onClose(Session session) {
        sessions.remove(session);

        // Mantem usuario após disconnect
        if (sessionToUser.containsKey(session)) {
            persistentUserIds.put(session.getId(), sessionToUser.get(session));
        }

        sessionToUser.remove(session);
        lastSeenIndex.remove(session);
    }

    /**
     * Manipula todas mensagens JSON enviadas por clients
     * O campo `type` determina a ação a ser performada
     */
    @OnMessage
    public void onMessage(String raw, Session sender) throws IOException {
        JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
        String type = obj.has("type") ? obj.get("type").getAsString() : "";

        switch (type) {

            // -----------------------------
            // USER LOGIN / PASSWORD SETUP
            // -----------------------------
            case "set_password": {
                String userId = obj.get("userId").getAsString();
                sessionToUser.put(sender, userId);
                persistentUserIds.put(sender.getId(), userId);

                // Cor de usuario e senha
                if (obj.has("userColor")) {
                    userColors.put(userId, obj.get("userColor").getAsString());
                }
                if (obj.has("password")) {
                    userPasswords.put(userId, obj.get("password").getAsString());
                }

                // Determina da onde comecar a pegar as mensagens
                int lastIndex = obj.has("lastMessageId")
                        ? getMessageIndexById(obj.get("lastMessageId").getAsString())
                        : lastSeenIndexByUser.getOrDefault(userId, 0);

                lastSeenIndexByUser.put(userId, messageHistory.size());

                sendMessageHistory(sender, lastIndex);
                break;
            }

            // -----------------------------
            // SEND NEW MESSAGE
            // -----------------------------
            case "send_message": {
                String userId = obj.get("userId").getAsString();
                String userName = obj.get("userName").getAsString();
                String userColor = obj.get("userColor").getAsString();
                String content = obj.get("content").getAsString();

                // Retorna a criptografia
                String password = userPasswords.get(userId);
                if (password == null) {
                    JsonObject err = new JsonObject();
                    err.addProperty("type", "error");
                    err.addProperty("message", "Password not set for user");
                    sender.getBasicRemote().sendText(gson.toJson(err));
                    break;
                }

                // Log para debug
                System.out.printf("|%-3s| (Password: %-1s) : %-10s%n", userName, password, content);

                // Criptografa a mensagem e guarda ela
                String encrypted = encryptWithRotatingDigits(content, password);
                String id = UUID.randomUUID().toString();
                String ts = Instant.now().toString();
                ChatMessage m = new ChatMessage(id, userId, userName, encrypted, ts);

                synchronized (messageHistory) {
                    messageHistory.add(m);
                }

                // Broadcast para todos usuarios
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

            // -----------------------------
            // ATTEMPT TO UNLOCK (BRUTEFORCE / GUESS)
            // -----------------------------
            case "attempt_unlock": {
                String requesterId = obj.get("requesterId").getAsString();
                String messageId = obj.get("messageId").getAsString();
                String guess = obj.get("guess").getAsString();

                // Acha a mensagem a ser desbloqueada
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

                /* 
                    * Compara a "sugestão" com a criptografia do usuario
                */
                String ownerPassword = userPasswords.get(target.ownerId);
                if (ownerPassword == null) {
                    reply.addProperty("success", false);
                    reply.addProperty("error", "owner password not set");
                    sendToRequester(requesterId, gson.toJson(reply));
                    break;
                }

                /* 
                    * Descriptografa a mensagem e manda ao usuario
                */
                if (ownerPassword.equals(guess)) {
                    String decrypted = decryptWithRotatingDigits(target.encryptedContent, ownerPassword);
                    reply.addProperty("success", true);
                    reply.addProperty("decryptedContent", decrypted);
                    reply.addProperty("ownerName", target.ownerName);
                } else {
                    // Envia uma tentativa de descriptografia
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

    // -----------------------------
    // ==== BROADCASTING / DELIVERY ====
    // -----------------------------

    /**
     * Envia uma mensagem para todos clients conectados
     * Manda apenas novas mensagens de acordo com o último
     * index visto pelo usuário
     */
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
                // Atualiza a última mensagem a ser vista pelo usuário
                lastSeenIndexByUser.put(userId, messageHistory.size());
            }
        }
    }

    /**
     * Manda uma mensagem JSON privada (por exemplo, "unlock_result") para o usuário
     * que enviou uma solicitação
     * Sends a private JSON message (e.g., unlock result) to the user who requested it.
     */
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

    // -----------------------------
    // ==== ENCRYPTION HELPERS ====
    // -----------------------------

    /**
     * Cifra de césar simples que desloca letras pelo valor numério da senha do usuario
     * É rotacionada a cada letra que é deslocada de acordo com a senha.
     */
    private static String encryptWithRotatingDigits(String text, String password) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit = Character.getNumericValue(password.charAt(i % password.length()));
            out.append(shiftChar(c, digit));
        }
        return out.toString();
    }

    /**
     * Inverte a rotaçao feita pela criptografia.
     */
    private static String decryptWithRotatingDigits(String text, String password) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit = Character.getNumericValue(password.charAt(i % password.length()));
            out.append(shiftChar(c, 26 - (digit % 26)));
        }
        return out.toString();
    }

    /**
     * Desloca uma letra de acordo com o valor.
     */
    private static char shiftChar(char c, int shift) {
        if (c >= 'a' && c <= 'z')
            return (char) ('a' + (c - 'a' + shift) % 26);
        if (c >= 'A' && c <= 'Z')
            return (char) ('A' + (c - 'A' + shift) % 26);
        return c;
    }
}
