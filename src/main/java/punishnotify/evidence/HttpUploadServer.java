package punishnotify.evidence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.ISO_8859_1))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Content-Disposition") && line.contains("filename=")) {
                    String filename = extractFilename(line);
                    if (filename == null || filename.isBlank()) {
                        skipPart(reader, boundary);
                        continue;
                    }

                    if (!isAllowedExtension(filename)) {
                        skipPart(reader, boundary);
                        continue;
                    }

                    reader.readLine();
                    String ctLine = reader.readLine();
                    reader.readLine();

                    Path file = tempDir.resolve(filename);
                    long bytesWritten = writeFileFromStream(reader, file, boundary);

                    if (bytesWritten > 0 && bytesWritten <= maxFileSizeBytes) {
                        files.add(file);
                    } else if (bytesWritten > maxFileSizeBytes) {
                        try { Files.deleteIfExists(file); } catch (Exception ignored) {}
                    }
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

    private long writeFileFromStream(BufferedReader reader, Path file, String boundary) throws IOException {
        long totalBytes = 0;
        java.io.FileOutputStream fos = new java.io.FileOutputStream(file.toFile());
        java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(fos);

        try {
            byte[] prevBytes = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(boundary)) {
                    break;
                }
                byte[] lineBytes = (line + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
                if (prevBytes != null) {
                    bos.write(prevBytes);
                    totalBytes += prevBytes.length;
                }
                prevBytes = lineBytes;

                if (totalBytes > maxFileSizeBytes + 1024) {
                    break;
                }
            }
            if (prevBytes != null) {
                bos.write(Arrays.copyOf(prevBytes, prevBytes.length - 2));
                totalBytes += prevBytes.length - 2;
            }
        } finally {
            bos.close();
        }
        return totalBytes;
    }

    private void skipPart(BufferedReader reader, String boundary) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains(boundary)) {
                break;
            }
        }
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
                    return filename;
                }
            }
        }
        return null;
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
