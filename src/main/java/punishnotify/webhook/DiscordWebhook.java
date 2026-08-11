package punishnotify.webhook;

import punishnotify.LocaleManager;
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
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DiscordWebhook {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private String webhookUrl;
    private String username;
    private String avatarUrl;
    private final HttpClient client;
    private final Logger logger;
    private LocaleManager lm;

    public DiscordWebhook(String webhookUrl, String username, String avatarUrl, Logger logger) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.username = username;
        this.avatarUrl = avatarUrl;
        this.logger = logger;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void setLocaleManager(LocaleManager lm) {
        this.lm = lm;
    }

    public void reload(String webhookUrl, String username, String avatarUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.username = username;
        this.avatarUrl = avatarUrl;
    }

    public boolean enabled() {
        return !webhookUrl.isEmpty();
    }

    public CompletableFuture<Boolean> sendAsync(PendingPunishment punishment, List<Path> files) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            String boundary = "----PunishNotify" + System.currentTimeMillis();
            byte[] body = buildMultipartBody(punishment, files, boundary);

            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
                        if (ok) {
                            logger.info(t("log.webhook_sent",
                                    punishment.playerName(),
                                    punishment.type().displayName(lm),
                                    response.statusCode()));
                        } else {
                            logger.warning(t("log.webhook_rejected",
                                    punishment.playerName(),
                                    punishment.type().displayName(lm),
                                    response.statusCode(),
                                    response.body()));
                        }
                        return ok;
                    })
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, t("log.webhook_send_error", ex.getMessage()));
                        return false;
                    });
        } catch (IOException e) {
            logger.log(Level.WARNING, t("log.webhook_prepare_error", e.getMessage()));
            return CompletableFuture.completedFuture(false);
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

        // Author
        String serverName = p.serverName() != null ? p.serverName() : "Survival";
        json.append("\"author\":{");
        json.append("\"name\":").append(jsonString(t("embed.server_prefix") + serverName));
        json.append(",\"icon_url\":").append(jsonString("https://mc-heads.net/avatar/MHF_Exclamation/100"));
        json.append("},");

        // Title and Color
        String typeName = p.type().displayName(lm);
        json.append("\"title\":").append(jsonString(p.type().emoji() + " " + typeName + t("embed.account_suffix")));
        json.append(",\"color\":").append(p.type().color());

        // Thumbnail (Player Head)
        json.append(",\"thumbnail\":{");
        String uuid = p.playerUuid() != null ? p.playerUuid().toString() : p.playerName();
        json.append("\"url\":").append(jsonString("https://mc-heads.net/avatar/" + uuid + "/100"));
        json.append("},");

        json.append("\"fields\":[");

        appendField(json, t("embed.field_player"), "``" + p.playerName() + "``", true);
        json.append(',');

        String moderatorDisplay = p.moderatorName() != null
                ? "``" + p.moderatorName() + "``"
                : "``" + t("embed.console") + "``";
        appendField(json, t("embed.field_moderator"), moderatorDisplay, true);
        json.append(',');

        if (!p.isPermanent()) {
            appendField(json, t("embed.field_duration"), p.durationText(), true);
        } else {
            appendField(json, t("embed.field_duration"), t("embed.permanent"), true);
        }
        json.append(',');

        String reason = (p.reason() != null && !p.reason().isBlank()) ? p.reason() : t("embed.no_reason");
        appendField(json, t("embed.field_reason"), reason, false);

        json.append("]");

        if (imageAttachmentUrl != null) {
            json.append(",\"image\":{\"url\":").append(jsonString(imageAttachmentUrl)).append("}");
        }

        json.append(",\"timestamp\":").append(jsonString(Instant.ofEpochMilli(p.createdAt()).toString()));
        json.append(",\"footer\":{");
        json.append("\"text\":").append(jsonString(t("embed.footer"))).append(",");
        json.append("\"icon_url\":\"https://mc-heads.net/avatar/MHF_Exclamation/100\"");
        json.append("}");

        json.append("}");
        json.append("]}");
        return json.toString();
    }

    /** Helper: get locale string, with graceful null-lm fallback. */
    private String t(String key, Object... args) {
        if (lm == null) return key;
        return lm.get(key, args);
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
