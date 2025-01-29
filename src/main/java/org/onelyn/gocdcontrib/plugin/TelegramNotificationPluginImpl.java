package org.onelyn.gocdcontrib.plugin;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.thoughtworks.go.plugin.api.GoApplicationAccessor;
import com.thoughtworks.go.plugin.api.GoPlugin;
import com.thoughtworks.go.plugin.api.GoPluginIdentifier;
import com.thoughtworks.go.plugin.api.annotation.Extension;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.onelyn.gocdcontrib.plugin.util.JSONUtils;
import java.io.Reader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Extension
@SuppressWarnings("unchecked")
public class TelegramNotificationPluginImpl implements GoPlugin {
    private static final String SETTINGS_PATH = "/var/go/.telegram_settings";
    private static final List<String> goSupportedVersions = List.of("1.0");
    public static final String EXTENSION_NAME = "notification";
    public static final String REQUEST_NOTIFICATIONS_INTERESTED_IN = "notifications-interested-in";
    public static final String REQUEST_STAGE_STATUS = "stage-status";
    public static final int SUCCESS_RESPONSE_CODE = 200;
    public static final int NOT_FOUND_RESPONSE_CODE = 404;
    public static final int INTERNAL_ERROR_RESPONSE_CODE = 500;
    private static final Gson GSON = new GsonBuilder().create();

    @Override
    public void initializeGoApplicationAccessor(GoApplicationAccessor accessor) {
        ensureSettingsFileExists();
    }

    @Override
    public GoPluginApiResponse handle(GoPluginApiRequest request) {
        if (REQUEST_NOTIFICATIONS_INTERESTED_IN.equals(request.requestName())) {
            return handleNotificationsInterestedIn();
        } else if (REQUEST_STAGE_STATUS.equals(request.requestName())) {
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
                lines.add("panic_chat_id=");
                lines.add("gocd_api_url=");
                lines.add("gocd_api_token=");
                Files.write(Paths.get(SETTINGS_PATH), lines, StandardCharsets.UTF_8);
            } else {
                List<String> lines = Files.readAllLines(Paths.get(SETTINGS_PATH));
                boolean changed = false;
                List<String> required = List.of("api_token=", "chat_id=", "panic_chat_id=", "gocd_api_url=", "gocd_api_token=");
                for (String req : required) {
                    if (lines.stream().noneMatch(line -> line.startsWith(req))) {
                        lines.add(req);
                        changed = true;
                    }
                }
                if (changed) {
                    Files.write(Paths.get(SETTINGS_PATH), lines, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException ignored) {
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

        Map<String, String> settings = readSettings();
        String apiToken = settings.getOrDefault("api_token", "").trim();
        String chatId = settings.getOrDefault("chat_id", "").trim();
        String panicChatId = settings.getOrDefault("panic_chat_id", "").trim();
        String gocdApiUrl = settings.getOrDefault("gocd_api_url", "").trim();
        String gocdApiToken = settings.getOrDefault("gocd_api_token", "").trim();

        try {
            Map<String, Object> pipelineMap = (Map<String, Object>) dataMap.get("pipeline");
            Map<String, Object> stageMap = (Map<String, Object>) pipelineMap.get("stage");

            String pipelineName = (String) pipelineMap.get("name");
            String stageName = (String) stageMap.get("name");
            String counterStr = (String) pipelineMap.get("counter");
            int currentCounter = Integer.parseInt(counterStr);

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

            String currentPipelineResult = fetchPipelineResult(gocdApiUrl, gocdApiToken, pipelineName, currentCounter);
            String prevPipelineResult = "N/A";

            if (currentCounter > 1) {
                prevPipelineResult = fetchPipelineResult(gocdApiUrl, gocdApiToken, pipelineName, currentCounter - 1);
            }

            String body = String.format(
                "Pipeline: %s\nStage: %s\nInstance: %s\nStatus: %s\nLast Run: %s\nStart Time: %s\nLast Transition Time: %s",
                pipelineName, stageName, counterStr, stageState, 
                "N/A".equals(prevPipelineResult) ? "No previous instance" : prevPipelineResult, fc, fl
            );

            if (!apiToken.isEmpty() && !chatId.isEmpty()) {
                sendTelegramMessage(apiToken, chatId, body);
            }

            if (!panicChatId.isEmpty() && "Failed".equalsIgnoreCase(currentPipelineResult)) {
                if (currentCounter > 1) {
                    if ("Failed".equalsIgnoreCase(prevPipelineResult) || "Passed".equalsIgnoreCase(prevPipelineResult)) {
                        String panicBody = String.format(
                            "[PANIC]\nPipeline: %s\nStage: %s\nInstance: %s\nPrevious Status: %s\nNew Status: %s\nEnd Time: %s",
                            pipelineName, stageName, counterStr, prevPipelineResult, currentPipelineResult, fl
                        );
                        sendTelegramMessage(apiToken, panicChatId, panicBody);
                    }
                }
            }

            resp.put("status", "success");
        } catch (Exception e) {
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

    private String fetchPipelineResult(String gocdApiUrl, String gocdApiToken, String pipelineName, int pipelineCounter) throws IOException {
        String url = String.format("%s/api/pipelines/%s/%d", gocdApiUrl, pipelineName, pipelineCounter);
        HttpClient client = HttpClients.createDefault();
        HttpGet get = new HttpGet(url);
        get.setHeader("Accept", "application/vnd.go.cd.v1+json");
        get.setHeader("Authorization", "Bearer " + gocdApiToken);

        HttpResponse response = client.execute(get);

        int code = response.getStatusLine().getStatusCode();

        if (code == 404) {
            return "N/A";
        }

        if (code >= 200 && code < 300) {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                try (InputStream in = entity.getContent()) {
                    Map<?, ?> pipelineJson = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Map.class);

                    Object stagesObj = pipelineJson.get("stages");

                    if (stagesObj instanceof List) {
                        List<?> stages = (List<?>) stagesObj;
                        boolean anyFailed = false;
                        boolean allPassed = !stages.isEmpty();

                        for (Object stageObj : stages) {
                            if (stageObj instanceof Map) {
                                Map<?, ?> stageMap = (Map<?, ?>) stageObj;
                                Object result = stageMap.get("result");

                                if (result != null) {
                                    String resultStr = result.toString();
                                    if ("Failed".equalsIgnoreCase(resultStr)) {
                                        anyFailed = true;
                                        allPassed = false;
                                        break;
                                    } else if (!"Passed".equalsIgnoreCase(resultStr)) {
                                        allPassed = false;
                                    }
                                } else {
                                    allPassed = false;
                                }
                            }
                        }

                        if (anyFailed) {
                            return "Failed";
                        } else if (allPassed) {
                            return "Passed";
                        }
                    }
                }
            }
        }

        return "Unknown";
    }

    private void sendTelegramMessage(String apiToken, String chatId, String text) throws IOException {
        HttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("https://api.telegram.org/bot" + apiToken + "/sendMessage");
        List<BasicNameValuePair> nd = new ArrayList<>();
        nd.add(new BasicNameValuePair("chat_id", chatId));
        nd.add(new BasicNameValuePair("text", text));
        nd.add(new BasicNameValuePair("parse_mode", "html"));
        httpPost.setEntity(new UrlEncodedFormEntity(nd, StandardCharsets.UTF_8));

        HttpResponse httpResponse = httpClient.execute(httpPost);

        HttpEntity entity = httpResponse.getEntity();
        if (entity != null) {
            try (InputStream ins = entity.getContent()) {
                Reader reader = new InputStreamReader(ins, StandardCharsets.UTF_8);
                Map<String, Object> content = (Map<String, Object>) GSON.fromJson(reader, Map.class);
                boolean ok = (boolean) content.getOrDefault("ok", false);

                if (!ok) {
                    String description = (String) content.getOrDefault("description", "");
                    throw new IOException("Telegram API error: " + description);
                }
            }
        }
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
                } else if (line.startsWith("panic_chat_id=")) {
                    r.put("panic_chat_id", line.substring("panic_chat_id=".length()));
                } else if (line.startsWith("gocd_api_url=")) {
                    r.put("gocd_api_url", line.substring("gocd_api_url=".length()));
                } else if (line.startsWith("gocd_api_token=")) {
                    r.put("gocd_api_token", line.substring("gocd_api_token=".length()));
                }
            }
        } catch (IOException ignored) {
        }
        return r;
    }

    private GoPluginApiResponse renderJSON(final int code, Object body) {
        String json = body == null ? null : GSON.toJson(body);
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
