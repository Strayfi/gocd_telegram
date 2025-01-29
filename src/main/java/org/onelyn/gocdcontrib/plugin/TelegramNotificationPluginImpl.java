package org.onelyn.gocdcontrib.plugin;

import com.google.gson.GsonBuilder;
import com.thoughtworks.go.plugin.api.GoApplicationAccessor;
import com.thoughtworks.go.plugin.api.GoPlugin;
import com.thoughtworks.go.plugin.api.GoPluginIdentifier;
import com.thoughtworks.go.plugin.api.annotation.Extension;
import com.thoughtworks.go.plugin.api.logging.Logger;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.onelyn.gocdcontrib.plugin.util.JSONUtils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Extension
@SuppressWarnings("unchecked")
public class TelegramNotificationPluginImpl implements GoPlugin {
    private static final Logger LOGGER = Logger.getLoggerFor(TelegramNotificationPluginImpl.class);
    private static final String SETTINGS_PATH = "/var/go/.telegram_settings";
    private static final List<String> goSupportedVersions = List.of("1.0");
    public static final String EXTENSION_NAME = "notification";
    public static final String REQUEST_NOTIFICATIONS_INTERESTED_IN = "notifications-interested-in";
    public static final String REQUEST_STAGE_STATUS = "stage-status";
    public static final int SUCCESS_RESPONSE_CODE = 200;
    public static final int NOT_FOUND_RESPONSE_CODE = 404;
    public static final int INTERNAL_ERROR_RESPONSE_CODE = 500;

    @Override
    public void initializeGoApplicationAccessor(GoApplicationAccessor accessor) {
        ensureSettingsFileExists();
    }

    @Override
    public GoPluginApiResponse handle(GoPluginApiRequest request) {
        String name = request.requestName();
        if (REQUEST_NOTIFICATIONS_INTERESTED_IN.equals(name)) {
            return handleNotificationsInterestedIn();
        } else if (REQUEST_STAGE_STATUS.equals(name)) {
            return handleStageNotification(request);
        }
        return renderJSON(NOT_FOUND_RESPONSE_CODE, null);
    }

    @Override
    public GoPluginIdentifier pluginIdentifier() {
        return new GoPluginIdentifier(EXTENSION_NAME, goSupportedVersions);
    }

    private void ensureSettingsFileExists() {
        try {
            if (!Files.exists(Paths.get(SETTINGS_PATH))) {
                List<String> lines = new ArrayList<>();
                lines.add("api_token=");
                lines.add("chat_id=");
                Files.write(Paths.get(SETTINGS_PATH), lines, StandardCharsets.UTF_8);
                LOGGER.info("Created empty settings file at " + SETTINGS_PATH);
            }
        } catch (IOException e) {
            LOGGER.error("Error creating empty settings file at " + SETTINGS_PATH, e);
        }
    }

    private GoPluginApiResponse handleNotificationsInterestedIn() {
        Map<String, Object> r = new HashMap<>();
        r.put("notifications", Collections.singletonList(REQUEST_STAGE_STATUS));
        return renderJSON(SUCCESS_RESPONSE_CODE, r);
    }

    private GoPluginApiResponse handleStageNotification(GoPluginApiRequest request) {
        Map<String, Object> dataMap = (Map<String, Object>) JSONUtils.fromJSON(request.requestBody());
        int responseCode = SUCCESS_RESPONSE_CODE;
        Map<String, Object> resp = new HashMap<>();
        List<String> messages = new ArrayList<>();
        Map<String, String> s = readSettings();
        String token = s.getOrDefault("api_token", "");
        String chat = s.getOrDefault("chat_id", "");
        try {
            Map<String, Object> pipelineMap = (Map<String, Object>) dataMap.get("pipeline");
            Map<String, Object> stageMap = (Map<String, Object>) pipelineMap.get("stage");
            String pipelineName = (String) pipelineMap.get("name");
            String stageName = (String) stageMap.get("name");
            String stageState = (String) stageMap.get("state");
            String createTimeString = (String) stageMap.get("create-time");
            String lastTransitionTimeString = (String) stageMap.get("last-transition-time");

            DateTimeFormatter iso = DateTimeFormatter.ISO_INSTANT;
            DateTimeFormatter out = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

            LocalDateTime ct = null;
            LocalDateTime lt = null;
            if (createTimeString != null && !createTimeString.isEmpty()) {
                ct = LocalDateTime.ofInstant(Instant.from(iso.parse(createTimeString)), ZoneId.systemDefault());
            }
            if (lastTransitionTimeString != null && !lastTransitionTimeString.isEmpty()) {
                lt = LocalDateTime.ofInstant(Instant.from(iso.parse(lastTransitionTimeString)), ZoneId.systemDefault());
            }

            String fc = (ct != null) ? out.format(ct) : "-";
            String fl = (lt != null) ? out.format(lt) : "-";

            String body = String.format(
                    "Pipeline: %s\nStage: %s\nStatus: %s\nTime start: %s\nLast Transition Time: %s",
                    pipelineName,
                    stageName,
                    stageState,
                    fc,
                    fl
            );

            HttpClient httpClient = HttpClients.createDefault();
            HttpPost httpPost = new HttpPost("https://api.telegram.org/bot" + token + "/sendMessage");
            List<NameValuePair> nd = new ArrayList<>();
            nd.add(new BasicNameValuePair("chat_id", chat));
            nd.add(new BasicNameValuePair("text", body));
            nd.add(new BasicNameValuePair("parse_mode", "html"));
            httpPost.setEntity(new UrlEncodedFormEntity(nd, StandardCharsets.UTF_8));

            HttpResponse httpResponse = httpClient.execute(httpPost);
            HttpEntity entity = httpResponse.getEntity();
            if (entity != null) {
                try (InputStream ins = entity.getContent()) {
                    Reader r = new InputStreamReader(ins, StandardCharsets.UTF_8);
                    Map<String, Object> content = (Map<String, Object>) new GsonBuilder().create().fromJson(r, Object.class);
                    boolean ok = (boolean) content.getOrDefault("ok", false);
                    if (ok) {
                        resp.put("status", "success");
                    } else {
                        responseCode = INTERNAL_ERROR_RESPONSE_CODE;
                        resp.put("status", "failure");
                        String description = (String) content.getOrDefault("description", "");
                        if (!description.isEmpty()) {
                            messages.add(description);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error occurred while trying to deliver a Telegram notification.", e);
            responseCode = INTERNAL_ERROR_RESPONSE_CODE;
            resp.put("status", "failure");
            if (e.getMessage() != null && !e.getMessage().trim().isEmpty()) {
                messages.add(e.getMessage());
            }
        }
        if (!messages.isEmpty()) {
            resp.put("messages", messages);
        }
        return renderJSON(responseCode, resp);
    }

    private Map<String, String> readSettings() {
        Map<String, String> r = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(Paths.get(SETTINGS_PATH));
            for (String line : lines) {
                if (line.startsWith("api_token=")) {
                    r.put("api_token", line.substring("api_token=".length()));
                } else if (line.startsWith("chat_id=")) {
                    r.put("chat_id", line.substring("chat_id=".length()));
                }
            }
        } catch (IOException ignored) {
        }
        return r;
    }

    private GoPluginApiResponse renderJSON(final int code, Object body) {
        String json = body == null ? null : new GsonBuilder().create().toJson(body);
        return new GoPluginApiResponse() {
            @Override
            public int responseCode() {
                return code;
            }
            @Override
            public Map<String, String> responseHeaders() {
                return null;
            }
            @Override
            public String responseBody() {
                return json;
            }
        };
    }
}
