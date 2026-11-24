package site.yuqi.admin.mediapipeline;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The few S3 calls a multipart upload needs, in memory, on a random local port. It does not check
 * signatures; S3SignerTest covers those against Amazon's published examples.
 */
final class FakeS3 implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, TreeMap<Integer, byte[]>> uploads = new ConcurrentHashMap<>();
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    record Request(String method, String path, String query, String authorization, String generationCondition) { }
    final List<Request> requests = new CopyOnWriteArrayList<>();
    volatile int deleteStatus = 204;
    volatile String deleteErrorBody = "";
    volatile boolean versioned;
    volatile String generation = "1";
    volatile boolean retainOnDelete;
    Consumer<String> beforeDelete = key -> { };
    private int nextId = 1;

    FakeS3() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String key = exchange.getRequestURI().getPath();
            requests.add(new Request(method, key, exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("x-goog-if-generation-match")));
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            byte[] body = exchange.getRequestBody().readAllBytes();
            String uploadId = query.get("uploadId");

            // GCS rejects mixing the Google precondition with AWS signing headers.
            boolean googleHeaders = exchange.getRequestHeaders().keySet().stream()
                    .anyMatch(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith("x-goog-"));
            boolean amazonHeaders = exchange.getRequestHeaders().keySet().stream()
                    .anyMatch(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith("x-amz-"));
            if (googleHeaders && amazonHeaders) {
                respond(exchange, 400, "<Error><Code>ExcessHeaderValues</Code></Error>");
                return;
            }

            if (method.equals("POST") && query.containsKey("uploads")) {
                String id = "upload-" + nextId++;
                uploads.put(id, new TreeMap<>());
                respond(exchange, 200, "<InitiateMultipartUploadResult><UploadId>" + id + "</UploadId></InitiateMultipartUploadResult>");
            } else if (method.equals("PUT") && uploadId != null) {
                TreeMap<Integer, byte[]> parts = uploads.get(uploadId);
                if (parts == null) { respond(exchange, 404, "<Error><Code>NoSuchUpload</Code></Error>"); return; }
                parts.put(Integer.parseInt(query.get("partNumber")), body);
                exchange.getResponseHeaders().add("ETag", "\"etag-" + query.get("partNumber") + "\"");
                respond(exchange, 200, "");
            } else if (method.equals("GET") && uploadId != null) {
                TreeMap<Integer, byte[]> parts = uploads.get(uploadId);
                if (parts == null) { respond(exchange, 404, "<Error><Code>NoSuchUpload</Code></Error>"); return; }
                StringBuilder xml = new StringBuilder("<ListPartsResult><IsTruncated>false</IsTruncated>");
                parts.forEach((number, bytes) -> xml.append("<Part><PartNumber>").append(number)
                        .append("</PartNumber><ETag>&quot;etag-").append(number).append("&quot;</ETag><Size>")
                        .append(bytes.length).append("</Size></Part>"));
                respond(exchange, 200, xml.append("</ListPartsResult>").toString());
            } else if (method.equals("POST") && uploadId != null) {
                TreeMap<Integer, byte[]> parts = uploads.remove(uploadId);
                if (parts == null) { respond(exchange, 404, "<Error><Code>NoSuchUpload</Code></Error>"); return; }
                ByteArrayOutputStream joined = new ByteArrayOutputStream();
                for (byte[] part : parts.values()) joined.write(part);
                objects.put(key, joined.toByteArray());
                respond(exchange, 200, "<CompleteMultipartUploadResult><ETag>&quot;joined&quot;</ETag></CompleteMultipartUploadResult>");
            } else if (method.equals("DELETE") && uploadId != null) {
                respond(exchange, uploads.remove(uploadId) == null ? 404 : 204, "");
            } else if (method.equals("DELETE") && query.isEmpty()) {
                beforeDelete.accept(key);
                if (!java.util.Objects.equals(generation, exchange.getRequestHeaders().getFirst("x-goog-if-generation-match"))) {
                    respond(exchange, 412, ""); return;
                }
                if (deleteStatus == 204 && !retainOnDelete) objects.remove(key);
                respond(exchange, deleteStatus, deleteErrorBody);
            } else if (method.equals("PUT") && query.isEmpty()) {
                objects.put(key, body);
                respond(exchange, 200, "");
            } else if (method.equals("GET") && query.isEmpty()) {
                byte[] object = objects.get(key);
                if (object == null) { respond(exchange, 404, ""); return; }
                exchange.sendResponseHeaders(200, object.length);
                exchange.getResponseBody().write(object);
                exchange.close();
            } else if (method.equals("HEAD")) {
                byte[] object = objects.get(key);
                if (object == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
                exchange.getResponseHeaders().add("ETag", "\"joined\"");
                exchange.getResponseHeaders().add("Content-Length", Integer.toString(object.length));
                if (versioned) exchange.getResponseHeaders().add("x-amz-version-id", "stored-version");
                if (generation != null) exchange.getResponseHeaders().add("x-goog-generation", generation);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else {
                respond(exchange, 400, "<Error><Code>Unsupported</Code></Error>");
            }
        } catch (RuntimeException unexpected) {
            respond(exchange, 500, "<Error><Code>" + unexpected + "</Code></Error>");
        }
    }

    boolean hasOpenUpload() {
        return !uploads.isEmpty();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new HashMap<>();
        if (raw == null) return query;
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            query.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (status == 204 || bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
