package cc.samaidev.samcdp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal threaded HTTP/1.1 server (Connection: close), no external deps. */
public abstract class HttpServer {

    public static class Request {
        public String method;
        public String path; // decoded, no query
        public Map<String, String> query = new LinkedHashMap<>();
        public Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];

        public String bodyString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    public static class Response {
        public final int status;
        public final String contentType;
        public final byte[] payload;

        public Response(int status, String contentType, byte[] payload) {
            this.status = status;
            this.contentType = contentType;
            this.payload = payload;
        }

        public static Response json(int status, String json) {
            return new Response(status, "application/json; charset=utf-8",
                    json.getBytes(StandardCharsets.UTF_8));
        }

        public static Response html(int status, String html) {
            return new Response(status, "text/html; charset=utf-8",
                    html.getBytes(StandardCharsets.UTF_8));
        }

        public static Response text(int status, String text) {
            return new Response(status, "text/plain; charset=utf-8",
                    text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private final int port;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    protected HttpServer(int port) {
        this.port = port;
    }

    /** Implement the routing logic. */
    protected abstract Response handle(Request req) throws Exception;

    public int port() {
        return port;
    }

    public synchronized void start() throws Exception {
        if (running) return;
        serverSocket = new ServerSocket(port, 64);
        serverSocket.setReuseAddress(true);
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    pool.execute(() -> serve(s));
                } catch (Exception e) {
                    if (running) sleep(50);
                }
            }
        }, "samcdp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        pool.shutdownNow();
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            Request req = new Request();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                socket.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                socket.close();
                return;
            }
            req.method = parts[0];
            String target = parts[1];

            // headers
            String line;
            int contentLength = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String k = line.substring(0, idx).trim().toLowerCase();
                    String v = line.substring(idx + 1).trim();
                    req.headers.put(k, v);
                    if (k.equals("content-length")) {
                        try {
                            contentLength = Integer.parseInt(v);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if (contentLength > 0 && contentLength < 8 * 1024 * 1024) {
                req.body = readN(in, contentLength);
            }

            // path + query
            String rawPath = target;
            String rawQuery = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                rawPath = target.substring(0, q);
                rawQuery = target.substring(q + 1);
            }
            req.path = URLDecoder.decode(rawPath, "UTF-8");
            for (String kv : rawQuery.split("&")) {
                if (kv.isEmpty()) continue;
                int eq = kv.indexOf('=');
                String k = eq < 0 ? kv : kv.substring(0, eq);
                String v = eq < 0 ? "" : kv.substring(eq + 1);
                req.query.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            }

            Response resp;
            try {
                resp = handle(req);
            } catch (Exception e) {
                String msg = "{\"ok\":false,\"error\":" + org.json.JSONObject.quote(
                        String.valueOf(e.getMessage())) + "}";
                resp = Response.json(500, msg);
            }
            if (resp == null) resp = Response.json(404, "{\"ok\":false,\"error\":\"not found\"}");

            String statusText = statusText(resp.status);
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(resp.status).append(' ').append(statusText).append("\r\n");
            head.append("Content-Type: ").append(resp.contentType).append("\r\n");
            head.append("Content-Length: ").append(resp.payload.length).append("\r\n");
            head.append("Connection: close\r\n");
            head.append("Server: samcdp-android/1.3.0\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.write(resp.payload);
            out.flush();
            socket.close();
        } catch (Exception ignored) {
            try {
                socket.close();
            } catch (Exception ignored2) {
            }
        }
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1, c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') break;
            if (prev != -1) buf.write(prev);
            prev = c;
        }
        byte[] bytes = buf.toByteArray();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readN(InputStream in, int n) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(n);
        byte[] chunk = new byte[8192];
        while (buf.size() < n) {
            int want = Math.min(chunk.length, n - buf.size());
            int r = in.read(chunk, 0, want);
            if (r < 0) break;
            buf.write(chunk, 0, r);
        }
        return buf.toByteArray();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
