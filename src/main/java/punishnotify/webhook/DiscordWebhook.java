package punishnotify.webhook;

import punishnotify.PendingPunishment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DiscordWebhook {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final String webhookUrl;
    private final String username;
    private final String avatarUrl;
    private final HttpClient client;
    private final Logger logger;

    public DiscordWebhook(String webhookUrl, String username, String avatarUrl, Logger logger) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.username = username;
        this.avatarUrl = avatarUrl;
        this.logger = logger;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean enabled() {
        return !webhookUrl.isEmpty();
    }

    public void sendAsync(PendingPunishment punishment, List<Path> files) {
        if (!enabled()) {
            return;
        }
        try {
            String boundary = "----PunishNotify" + System.currentTimeMillis();
            byte[] body = buildMultipartBody(punishment, files, boundary);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(response -> {
                        int code = response.statusCode();
                        if (code >= 200 && code < 300) {
                            logger.info("Вебхук отправлен для " + punishment.playerName()
                                    + " (" + punishment.type().displayName() + "), HTTP " + code);
                        } else {
                            logger.warning("Вебхук для " + punishment.playerName()
                                    + " (" + punishment.type().displayName() + ") отклонён Discord: HTTP " + code);
                        }
                    })
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "Ошибка отправки вебхука: " + ex.getMessage());
                        return null;
                    });
        } catch (IOException e) {
            logger.log(Level.WARNING, "Ошибка подготовки вебхука: " + e.getMessage());
        }
    }

    private byte[] buildMultipartBody(PendingPunishment p, List<Path> files, String boundary) throws IOException {
        List<String> filenames = new ArrayList<>();
        for (Path file : files) {
            filenames.add(file.getFileName().toString());
        }
        String imageAttachment = findFirstImageAttachment(files);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        writePart(out, boundary, "payload_json", "application/json", buildEmbedJson(p, imageAttachment));

        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            byte[] data = Files.readAllBytes(file);
            String contentType = guessContentType(file);
            String filename = escapeFormField(filenames.get(i));
            writeFilePart(out, boundary, "files[" + i + "]", filename, contentType, data);
        }

        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String findFirstImageAttachment(List<Path> files) {
        for (Path file : files) {
            if (guessContentType(file).startsWith("image/")) {
                return "attachment://" + file.getFileName();
            }
        }
        return null;
    }

    String buildEmbedJson(PendingPunishment p, String imageAttachmentUrl) {
        StringBuilder json = new StringBuilder();

        json.append("{\"username\":");
        json.append(jsonString(username));
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            json.append(",\"avatar_url\":").append(jsonString(avatarUrl));
        }

        json.append(",\"embeds\":[");
        json.append("{");
        json.append("\"title\":").append(jsonString(p.type().emoji() + " " + p.type().displayName() + ": " + p.playerName()));
        json.append(",\"color\":").append(p.type().color());
        json.append(",\"timestamp\":").append(jsonString(Instant.ofEpochMilli(p.createdAt()).toString()));
        json.append(",\"fields\":[");

        appendField(json, "Игрок", "`" + p.playerName() + "`\n" + p.playerUuid(), true);
        json.append(',');
        appendField(json, "Модератор", p.moderatorName() != null ? "`" + p.moderatorName() + "`" : "Консоль", true);
        json.append(',');

        if (p.reason() != null && !p.reason().isBlank()) {
            appendField(json, "Причина", p.reason(), false);
            json.append(',');
        }

        if (!p.isPermanent()) {
            appendField(json, "Длительность", p.durationText(), true);
            json.append(',');
        }

        appendField(json, "Сервер", p.serverName() != null ? p.serverName() : "Неизвестен", true);
        json.append(',');
        appendField(json, "Время", TIME_FORMAT.format(Instant.ofEpochMilli(p.createdAt())), true);

        json.append("],");
        if (imageAttachmentUrl != null) {
            json.append("\"image\":{\"url\":").append(jsonString(imageAttachmentUrl)).append("},");
        }
        json.append("\"footer\":{\"text\":\"PunishNotify\"}}");

        json.append("]}");
        return json.toString();
    }

    private void appendField(StringBuilder json, String name, String value, boolean inline) {
        json.append("{\"name\":").append(jsonString(name));
        json.append(",\"value\":").append(jsonString(truncate(value, 1024)));
        json.append(",\"inline\":").append(inline).append('}');
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    static String jsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void writePart(ByteArrayOutputStream out, String boundary,
                                  String name, String contentType, String content) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFilePart(ByteArrayOutputStream out, String boundary,
                                       String name, String filename, String contentType,
                                       byte[] data) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeFormField(String s) {
        return s.replace("\"", "\\\"");
    }

    private static String guessContentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}
