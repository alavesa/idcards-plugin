package fi.alavesa.idcards.resourcepack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP host for the pack (JDK built-in server, separate port). Serves exactly one path,
 * /idcards/&lt;sha1&gt;.zip; everything else is 404. Runs on two daemon threads, never on the main thread.
 */
final class PackHttpServer {

    private final HttpServer server;
    private final ExecutorService pool;
    private volatile byte[] zip = new byte[0];
    private volatile String path = "/idcards/none.zip";

    PackHttpServer(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "idcards-pack-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    void serve(byte[] zip, String sha1) {
        this.zip = zip;
        this.path = "/idcards/" + sha1 + ".zip";
    }

    void stop() {
        server.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            boolean get = ex.getRequestMethod().equals("GET");
            boolean head = ex.getRequestMethod().equals("HEAD");
            if ((!get && !head) || !ex.getRequestURI().getPath().equals(path)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = zip;
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            ex.sendResponseHeaders(200, head ? -1 : body.length);
            if (get) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }
}
