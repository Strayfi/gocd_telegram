package org.onelyn.gocdcontrib.plugin;

public class PluginSettings {
    private String apiToken;
    private String chatId;

    public PluginSettings(String apiToken, String chatId) {
        this.apiToken = apiToken;
        this.chatId = chatId;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PluginSettings that = (PluginSettings) o;

        if (!apiToken.equals(that.apiToken)) return false;
        if (!chatId.equals(that.chatId)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = apiToken != null ? apiToken.hashCode() : 0;
        result = 31 * result + (chatId != null ? chatId.hashCode() : 0);

        return result;
    }
}
