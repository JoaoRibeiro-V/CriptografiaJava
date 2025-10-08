package chat;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/ws")
public class ChatEndpoint {

    private static final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private static final List<String> messageHistory = new ArrayList<>(); // historico de mensagens
    @OnOpen
    public void onOpen(Session session) throws IOException {
        sessions.add(session);
        System.out.println("Client connected: " + session.getId());

        // pega e manda o historico pra usuario novo
        synchronized (messageHistory) {
            for (String pastMessage : messageHistory) {
                session.getBasicRemote().sendText(pastMessage);
            }
        }
    }

    @OnClose
    public void onClose(Session session) {
        sessions.remove(session);
        System.out.println("Client disconnected: " + session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session sender) throws IOException {
        if (message.contains("\"type\":\"ping\"")) return; // ignore heartbeat

        // log
        String logMsg = message.replace("{", "")
                               .replace("}", "")
                               .replace("\"", "")
                               .replace(",", " | ");
        System.out.println("[Chat] " + logMsg);

        // salva pro historico
        synchronized (messageHistory) {
            messageHistory.add(message);
        }

        // manda para client
        for (Session s : sessions) {
            if (s.isOpen()) {
                s.getBasicRemote().sendText(message);
            }
        }
    }
}
