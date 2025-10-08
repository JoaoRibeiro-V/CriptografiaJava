package chat;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;

public class ChatServer {
    public static void main(String[] args) throws Exception {
        // roda na porta 8080
        Server server = new Server(8080);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        // Websocket
        JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            wsContainer.addEndpoint(ChatEndpoint.class);
        });

        System.out.print("\033\143");

        server.start();
        System.out.println("✅ Chat server running on ws://localhost:8080/ws");
        server.join();
    }
}
