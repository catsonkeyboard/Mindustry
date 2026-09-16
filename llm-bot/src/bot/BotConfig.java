package bot;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.serialization.Jval;

/**
 * Configuration for the LLM bot.
 * Priority: environment variables > config file > defaults.
 * Config file location: {@code <dataDirectory>/mods/llm-bot/config.json}
 * (on a dedicated server this resolves to {@code config/mods/llm-bot/config.json}).
 */
public class BotConfig{
    public static final String[] CATALOG = {
        "conveyor", "router", "junction", "duct",
        "mechanical-drill", "pneumatic-drill", "laser-drill",
        "graphite-press", "silicon-smelter", "kiln", "pulverizer",
        "combustion-generator", "steam-generator", "thermal-generator",
        "node1", "battery1",
        "duo", "scatter", "salvo", "ripple", "fuse", "cyclone",
        "copper-wall", "copper-wall-large", "titanium-wall", "titanium-wall-large",
        "mender", "mend-projector", "overdrive-projector", "overdrive-dome",
        "gate1", "gate2"
    };

    /** Whether to activate the bot at all. */
    public boolean enabled = true;
    /** OpenAI-compatible API base url, without trailing slash. */
    public String baseUrl = "https://api.openai.com/v1";
    /** API key. Required. */
    public String apiKey = "";
    /** Model id, e.g. deepseek-chat / gpt-4o-mini / qwen2.5:14b (ollama). */
    public String model = "gpt-4o-mini";
    /** Team name to control. "default" = the map's default team. Otherwise a {@link mindustry.game.Team} name, e.g. "sharded". */
    public String team = "default";
    /** Seconds between autonomous decisions. */
    public int intervalSec = 20;
    /** Maximum actions executed per decision. */
    public int maxActions = 5;
    /** LLM sampling temperature. */
    public double temperature = 0.4;

    private BotConfig(){
    }

    public static BotConfig load(){
        BotConfig config = new BotConfig();

        Fi file = Core.settings.getDataDirectory().child("mods/llm-bot/config.json");
        try{
            if(file.exists()){
                Jval json = Jval.read(file.readString());
                config.enabled = json.getBool("enabled", config.enabled);
                config.baseUrl = json.getString("baseUrl", config.baseUrl);
                config.apiKey = json.getString("apiKey", config.apiKey);
                config.model = json.getString("model", config.model);
                config.team = json.getString("team", config.team);
                config.intervalSec = json.getInt("intervalSec", config.intervalSec);
                config.maxActions = json.getInt("maxActions", config.maxActions);
                config.temperature = json.getDouble("temperature", config.temperature);
                Log.info("[llm-bot] Loaded config from @", file.absolutePath());
            }else{
                writeDefault(file);
            }
        }catch(Exception e){
            Log.err("[llm-bot] Failed to read config file, using defaults", e);
        }

        //environment overrides
        String envEnabled = System.getenv("LLM_BOT_ENABLED");
        if(envEnabled != null) config.enabled = envEnabled.equals("true");
        config.baseUrl = env("LLM_BOT_BASE_URL", config.baseUrl);
        config.apiKey = env("LLM_BOT_API_KEY", config.apiKey);
        config.model = env("LLM_BOT_MODEL", config.model);
        config.team = env("LLM_BOT_TEAM", config.team);

        if(config.apiKey.isEmpty()){
            Log.warn("[llm-bot] No API key configured. Set 'apiKey' in @ or the LLM_BOT_API_KEY env var.", file.absolutePath());
        }
        return config;
    }

    private static void writeDefault(Fi file){
        try{
            file.parent().mkdirs();
            file.writeString(
                "{\n" +
                "  \"enabled\": true,\n" +
                "  \"baseUrl\": \"https://api.openai.com/v1\",\n" +
                "  \"apiKey\": \"sk-...\",\n" +
                "  \"model\": \"gpt-4o-mini\",\n" +
                "  \"team\": \"default\",\n" +
                "  \"intervalSec\": 20,\n" +
                "  \"maxActions\": 5,\n" +
                "  \"temperature\": 0.4\n" +
                "}\n");
            Log.info("[llm-bot] Wrote default config to @", file.absolutePath());
        }catch(Exception e){
            Log.err("[llm-bot] Failed to write default config", e);
        }
    }

    private static String env(String key, String fallback){
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
