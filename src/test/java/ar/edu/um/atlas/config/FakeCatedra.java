package ar.edu.um.atlas.config;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor HTTP local que hace de cátedra en los tests: responde en orden lo que se le programa
 * (la última respuesta se repite) y registra cada pedido. Sin red externa.
 */
final class FakeCatedra implements AutoCloseable {

    record Reply(int status, String contentType, String body, long delayMillis, String location) {
        static Reply json(int status, String body) {
            return new Reply(status, "application/json", body, 0, null);
        }

        static Reply problem(int status, String body) {
            return new Reply(status, "application/problem+json", body, 0, null);
        }

        static Reply slow(long delayMillis, String body) {
            return new Reply(200, "application/json", body, delayMillis, null);
        }

        static Reply redirect(String location) {
            return new Reply(302, "text/plain", "", 0, location);
        }
    }

    record Request(String method, String path, String authorization) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Queue<Reply> replies = new ArrayDeque<>();
    private final List<Request> requests = new ArrayList<>();
    private Reply last = Reply.json(500, "{}");

    FakeCatedra(Reply... replies) throws IOException {
        this.replies.addAll(List.of(replies));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            Reply reply;
            synchronized (this) {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization")));
                reply = this.replies.isEmpty() ? last : this.replies.poll();
                last = reply;
            }
            try {
                Thread.sleep(reply.delayMillis());
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", reply.contentType());
                if (reply.location() != null) {
                    exchange.getResponseHeaders().add("Location", reply.location());
                }
                exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
            } catch (InterruptedException | IOException ignored) {
                // el cliente cortó por timeout
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(executor); // sin esto, una respuesta lenta bloquea los pedidos siguientes
        server.start();
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
