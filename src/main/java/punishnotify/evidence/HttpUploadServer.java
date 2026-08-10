package punishnotify.evidence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class HttpUploadServer {

    private static final List<String> ALLOWED_EXTENSIONS = List.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp",
            ".mp4", ".webm", ".pdf"
    );

    private HttpServer server;
    private final Logger logger;
    private final EvidenceManager evidenceManager;
    private String uploadPageHtml;
    private long maxFileSizeBytes;
    private int maxFiles;

    public HttpUploadServer(Logger logger, EvidenceManager evidenceManager) {
        this.logger = logger;
        this.evidenceManager = evidenceManager;
    }

    public void loadConfig(int port, String bind, long maxFileSizeMb, int maxFiles) {
        this.maxFileSizeBytes = maxFileSizeMb * 1024 * 1024;
        this.maxFiles = maxFiles;
    }

    public void start(int port, String bind) {
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            server.createContext("/", this::handleRoot);
            server.createContext("/upload", this::handleUpload);
            server.createContext("/skip", this::handleSkip);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            logger.info("HTTP-сервер загрузки запущен на " + bind + ":" + port);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Не удалось запустить HTTP-сервер: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("HTTP-сервер загрузки остановлен.");
        }
    }

    public void setUploadPageHtml(String html) {
        this.uploadPageHtml = html;
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        try {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query != null ? query : "");

            if (!params.containsKey("token") || !evidenceManager.hasToken(params.get("token"))) {
                sendResponse(exchange, 403, "Forbidden");
                return;
            }

            String html = uploadPageHtml != null ? uploadPageHtml : "<h1>PunishNotify</h1><p>Upload page not loaded.</p>";
            html = html.replace("{{TOKEN}}", params.get("token"));

            sendResponse(exchange, 200, html, "text/html; charset=utf-8");
        } catch (Exception e) {
            logger.log(Level.WARNING, "HTTP ошибка (GET /): " + e.getMessage());
            sendResponse(exchange, 500, "Internal Server Error");
        }
    }

    private void handleUpload(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query != null ? query : "");

            String token = params.get("token");
            if (token == null || !evidenceManager.hasToken(token)) {
                sendResponse(exchange, 403, "{\"error\":\"Invalid or expired token\"}");
                return;
            }

            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.contains("multipart/form-data")) {
                sendResponse(exchange, 400, "{\"error\":\"Expected multipart/form-data\"}");
                return;
            }

            String boundary = extractBoundary(contentType);
            if (boundary == null) {
                sendResponse(exchange, 400, "{\"error\":\"Missing boundary\"}");
                return;
            }

            List<Path> uploadedFiles = parseMultipartUpload(exchange, boundary);

            if (uploadedFiles.isEmpty()) {
                sendResponse(exchange, 400, "{\"error\":\"No files uploaded\"}");
                return;
            }

            evidenceManager.completeWithEvidence(token, uploadedFiles);
            sendResponse(exchange, 200, "{\"status\":\"ok\",\"files\":" + uploadedFiles.size() + "}");

        } catch (Exception e) {
            logger.log(Level.WARNING, "HTTP ошибка (POST /upload): " + e.getMessage());
            sendResponse(exchange, 500, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    private void handleSkip(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query != null ? query : "");

            String token = params.get("token");
            if (token == null || !evidenceManager.hasToken(token)) {
                sendResponse(exchange, 403, "{\"error\":\"Invalid or expired token\"}");
                return;
            }

            evidenceManager.skip(token);
            sendResponse(exchange, 200, "{\"status\":\"skipped\"}");

        } catch (Exception e) {
            logger.log(Level.WARNING, "HTTP ошибка (POST /skip): " + e.getMessage());
            sendResponse(exchange, 500, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    private List<Path> parseMultipartUpload(HttpExchange exchange, String boundary) throws IOException {
        List<Path> files = new ArrayList<>();
        Path tempDir = Files.createTempDirectory("punishnotify_");
        byte[] delim = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

        try (BufferedInputStream in = new BufferedInputStream(exchange.getRequestBody())) {
            String firstLine = readAsciiLine(in);
            if (firstLine == null || !firstLine.startsWith("--" + boundary)) {
                throw new IOException("Неверный формат multipart: нет начальной границы");
            }

            boolean finished = false;
            while (!finished) {
                String filename = null;
                String headerLine;
                while ((headerLine = readAsciiLine(in)) != null && !headerLine.isEmpty()) {
                    if (headerLine.startsWith("Content-Disposition") && headerLine.contains("filename=")) {
                        filename = extractFilename(headerLine);
                        filename = filename == null ? null : sanitizeFilename(filename);
                    }
                }
                if (headerLine == null) {
                    break;
                }

                if (filename == null || !isAllowedExtension(filename)) {
                    consumePartContent(in, delim, null, Long.MAX_VALUE);
                } else if (files.size() >= maxFiles) {
                    throw new IOException("Превышен лимит файлов: " + maxFiles);
                } else {
                    Path file = tempDir.resolve(filename);
                    long bytesWritten = consumePartContent(in, delim, file, maxFileSizeBytes);
                    if (bytesWritten > 0) {
                        files.add(file);
                    } else {
                        try { Files.deleteIfExists(file); } catch (Exception ignored) {}
                    }
                }

                String tail = readAsciiLine(in);
                if (tail == null) {
                    break;
                }
                if ("--".equals(tail)) {
                    finished = true;
                }
            }
        } catch (Exception e) {
            for (Path f : files) {
                try { Files.deleteIfExists(f); } catch (Exception ignored) {}
            }
            throw e;
        }

        if (files.isEmpty()) {
            try { Files.deleteIfExists(tempDir); } catch (Exception ignored) {}
        }

        return files;
    }

    private long consumePartContent(BufferedInputStream in, byte[] delim, Path file, long maxBytes) throws IOException {
        long total = 0;
        boolean tooBig = false;
        byte[] tail = Arrays.copyOfRange(delim, 2, delim.length);

        try (OutputStream out = file != null ? Files.newOutputStream(file) : OutputStream.nullOutputStream()) {
            while (true) {
                int b = in.read();
                if (b == -1) {
                    break;
                }
                if (b == '\r') {
                    in.mark(delim.length + 16);
                    int c = in.read();
                    if (c == '\n') {
                        byte[] rest = in.readNBytes(tail.length);
                        if (Arrays.equals(rest, tail)) {
                            break;
                        }
                    }
                    in.reset();
                }
                if (!tooBig) {
                    out.write(b);
                    total++;
                    if (total > maxBytes) {
                        tooBig = true;
                    }
                }
            }
        }
        return tooBig ? -1 : total;
    }

    private String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }
            buf.write(b);
        }
        if (buf.size() == 0) {
            return null;
        }
        byte[] bytes = buf.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len--;
        }
        return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
    }

    private String extractBoundary(String contentType) {
        for (String part : contentType.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                return part.substring("boundary=".length()).replace("\"", "");
            }
        }
        return null;
    }

    private String extractFilename(String contentDisposition) {
        for (String part : contentDisposition.split(";")) {
            part = part.trim();
            if (part.startsWith("filename=")) {
                String filename = part.substring("filename=".length()).replace("\"", "");
                if (!filename.isBlank()) {
                    return decodeUtf8Filename(filename);
                }
            }
        }
        return null;
    }

    private String decodeUtf8Filename(String filename) {
        byte[] bytes = filename.getBytes(StandardCharsets.ISO_8859_1);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String sanitizeFilename(String filename) {
        String sanitized = filename.replace('\\', '_').replace('/', '_');
        if (sanitized.contains("..") || sanitized.isBlank()) {
            return null;
        }
        return sanitized;
    }

    private boolean isAllowedExtension(String filename) {
        String lower = filename.toLowerCase();
        return ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private Map<String, String> parseQuery(String query) {
        return Arrays.stream(query.split("&"))
                .map(s -> s.split("=", 2))
                .filter(a -> a.length == 2)
                .collect(Collectors.toMap(a -> a[0], a -> a[1]));
    }

    private void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
        sendResponse(exchange, code, body, "application/json; charset=utf-8");
    }

    private void sendResponse(HttpExchange exchange, int code, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
