package bot;

import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import bot.actions.ActionExecutor;
import bot.agent.AgentLoop;
import bot.llm.LlmClient;
import bot.perception.StateSerializer;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.mod.Plugin;

import static mindustry.Vars.state;

/**
 * Entry point of the LLM bot plugin.
 * Wiring: WorldLoadEvent -> resolve team + rescan ores + activate loop,
 * WaveEvent -> flag urgent decision, ResetEvent -> deactivate.
 */
public class LlmBotPlugin extends Plugin{
    private static final String TAG = "[llm-bot]";

    private BotConfig config;
    private StateSerializer serializer;
    private ActionExecutor executor;
    private AgentLoop loop;

    @Override
    public void init(){
        config = BotConfig.load();
        if(!config.enabled){
            Log.info("@ disabled in config, skipping activation", TAG);
            return;
        }
        if(config.apiKey == null || config.apiKey.isEmpty() || config.apiKey.equals("sk-...")){
            Log.err("@ no API key configured (config/mods/llm-bot/config.json or LLM_BOT_API_KEY); bot will stay inactive", TAG);
            return;
        }

        serializer = new StateSerializer();
        executor = new ActionExecutor(config);
        loop = new AgentLoop(config, new LlmClient(config), serializer, executor);

        //world lifecycle
        Events.on(EventType.WorldLoadEvent.class, e -> {
            try{
                if(!state.isGame()) return;
                Team team = resolveTeam(config.team);
                serializer.setTeam(team);
                serializer.rescan();
                executor.setTeam(team);
                loop.activate();
                Log.info("@ activated for team '@' (core: @)", TAG, team.name, team.data().hasCore());
            }catch(Throwable t){
                Log.err(TAG, "failed to activate on world load", t);
            }
        });

        Events.on(EventType.ResetEvent.class, e -> loop.deactivate());
        Events.on(EventType.WaveEvent.class, e -> loop.urgent());

        loop.start();
        Log.info("@ loaded (interval=@s, model=@, baseUrl=@)", TAG, config.intervalSec, config.model, config.baseUrl);
    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("llm", "Show LLM bot status", args -> {
            if(loop == null){
                Log.info("@ not active (disabled or no API key)", TAG);
                return;
            }
            Log.info("@ @", TAG, loop.status());
        });
    }

    private Team resolveTeam(String name){
        if(name == null || name.isEmpty() || name.equals("default")){
            return state.rules.defaultTeam;
        }
        for(Team team : Team.all){
            if(team.name.equalsIgnoreCase(name)) return team;
        }
        Log.warn("@ unknown team '@', falling back to default team", TAG, name);
        return state.rules.defaultTeam;
    }
}
