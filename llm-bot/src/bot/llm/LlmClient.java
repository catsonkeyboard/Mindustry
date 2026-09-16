package bot.llm;

import arc.util.Log;
import arc.util.serialization.Jval;
import bot.BotConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal OpenAI-compatible chat completions client.
 * Works with OpenAI, DeepSeek, Moonshot, Qwen, OpenRouter, Ollama (/v1) and any compatible gateway.
 * Uses only the JDK HttpClient + Arc's Jval, so the plugin jar has zero external dependencies.
 */
public class LlmClient{
    private static final String TAG = "[llm-bot]";

    private final BotConfig config;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public LlmClient(BotConfig config){
        this.config = config;
    }

    /**
     * Runs one chat completion round.
     * @return the assistant message content.
     * @throws Exception on network failure, non-2xx status or malformed response.
     */
    public String chat(String systemPrompt, String userPrompt) throws Exception{
        Jval body = Jval.newObject();
        body.put("model", config.model);
        body.put("temperature", config.temperature);
        Jval messages = Jval.newArray();
        Jval sys = Jval.newObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        Jval user = Jval.newObject();
        user.put("role", "user");
        user.put("content", userPrompt);
        messages.add(sys);
        messages.add(user);
        body.put("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(trimSlash(config.baseUrl) + "/chat/completions"))
            .timeout(Duration.ofSeconds(90))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        Log.debug("@ POST @/chat/completions (model=@)", TAG, trimSlash(config.baseUrl), config.model);

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if(response.statusCode() / 100 != 2){
            throw new Exception("LLM API returned HTTP " + response.statusCode() + ": " + truncate(response.body(), 400));
        }

        Jval json = Jval.read(response.body());
        String content = json.get("choices").asArray().first().get("message").getString("content", "");
        if(content.isEmpty()){
            throw new Exception("LLM API returned empty content");
        }
        return content;
    }

    /** Extracts the first JSON object embedded in the model output (models sometimes wrap JSON in markdown fences). */
    public static Jval extractJson(String raw){
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if(start == -1 || end == -1 || end <= start){
            throw new IllegalArgumentException("No JSON object found in LLM output: " + truncate(raw, 200));
        }
        return Jval.read(raw.substring(start, end + 1));
    }

    private static String trimSlash(String s){
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max){
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }
}
