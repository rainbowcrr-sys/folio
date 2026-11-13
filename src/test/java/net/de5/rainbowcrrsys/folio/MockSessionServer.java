package net.de5.rainbowcrrsys.folio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Loopback stand-in for the Mojang session service, so online-mode integration tests never
 * touch a real service and never need credentials. Uses only java.base classes.
 *
 * <p>A response mode can be selected to exercise the vanilla failure paths: 200 (verified
 * profile), 204 (no such session — must be refused), 403 (Forbidden), or a profile whose
 * name does not match the login name.
 */
final class MockSessionServer implements AutoCloseable {

    enum Mode { OK, NO_CONTENT, FORBIDDEN, NAME_MISMATCH }

    private static final String PROFILE =
            "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"FolioTest\"}";

    private final ServerSocket socket;
    private final Thread thread;
    private final Mode mode;
    private volatile boolean running = true;
    private volatile String lastQuery = "";

    MockSessionServer() throws IOException {
        this(Mode.OK);
    }

    MockSessionServer(Mode mode) throws IOException {
        this.mode = mode;
        socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        thread = new Thread(this::serve, "folio-mock-session");
        thread.setDaemon(true);
        thread.start();
    }

    String url() {
        return "http://127.0.0.1:" + socket.getLocalPort() + "/session/minecraft/hasJoined";
    }

    String lastQuery() {
        return lastQuery;
    }

    private void serve() {
        while (running) {
            try (Socket client = socket.accept()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("GET ")) lastQuery = line;
                }
                byte[] body;
                String status;
                switch (mode) {
                    case NO_CONTENT:
                        status = "204 No Content";
                        body = new byte[0];
                        break;
                    case FORBIDDEN:
                        status = "403 Forbidden";
                        body = "{\"error\":\"Forbidden\"}".getBytes(StandardCharsets.UTF_8);
                        break;
                    case NAME_MISMATCH:
                        status = "200 OK";
                        body = ("{\"id\":\"069a79f444e94726a5befca90e38aaf5\","
                                + "\"name\":\"SomeoneElse\"}").getBytes(StandardCharsets.UTF_8);
                        break;
                    case OK:
                    default:
                        status = "200 OK";
                        body = PROFILE.getBytes(StandardCharsets.UTF_8);
                        break;
                }
                String head = "HTTP/1.1 " + status + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream out = client.getOutputStream();
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (IOException e) {
                if (running) return;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing during teardown is best effort.
        }
    }
}
