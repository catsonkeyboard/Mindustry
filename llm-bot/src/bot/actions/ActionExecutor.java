package bot.actions;

import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import bot.BotConfig;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;

import static mindustry.Vars.*;

/**
 * Validates and executes LLM-issued actions on the main game thread.
 * (Executed only on the main thread via Core.app.post from AgentLoop.)
 * Every action is treated as untrusted input: unknown blocks, out-of-bounds
 * coordinates, invalid placements and unaffordable costs are rejected with
 * a human-readable reason that is fed back into the next prompt.
 */
public class ActionExecutor{
    private static final String TAG = "[llm-bot]";

    private final BotConfig config;
    private Team team;

    public ActionExecutor(BotConfig config){
        this.config = config;
    }

    public void setTeam(Team team){
        this.team = team;
    }

    public boolean ready(){
        return team != null && state.isGame() && team.data() != null && team.data().hasCore();
    }

    /** Executes the parsed actions array. @return result strings for the feedback loop. */
    public Seq<String> execute(Jval actions){
        Seq<String> results = new Seq<>();
        if(actions == null || !actions.isArray()){
            results.add("actions 字段缺失或格式错误");
            return results;
        }

        int executed = 0;
        for(Jval action : actions.asArray()){
            if(executed >= config.maxActions){
                results.add("已达单轮动作上限(" + config.maxActions + "),其余动作被跳过");
                break;
            }
            String type = action.getString("type", "");
            try{
                switch(type){
                    case "build" -> {
                        results.add(doBuild(action));
                        executed++;
                    }
                    case "chat" -> {
                        results.add(doChat(action));
                        executed++;
                    }
                    case "idle" -> {
                        results.add("本轮未行动");
                    }
                    default -> results.add("未知动作类型: " + type);
                }
            }catch(Exception e){
                Log.err(TAG, "动作执行异常", e);
                results.add("动作执行异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
                executed++;
            }
        }
        return results;
    }

    private String doBuild(Jval action){
        String blockName = action.getString("block", "");
        int x = action.getInt("x", Integer.MIN_VALUE);
        int y = action.getInt("y", Integer.MIN_VALUE);
        int rotation = action.getInt("rotation", 0);

        //1. resolve block
        Block block = content.block(blockName);
        if(block == null || block.isHidden()){
            return "拒绝建造 [" + blockName + "]: 不是目录中的合法方块";
        }
        if(!Build.validPlace(block, team, x, y, rotation)){
            return "拒绝建造 [" + blockName + "] 于 (" + x + "," + y + "): 位置不合法(地形阻挡/与他物重叠/超出核心范围)";
        }

        Tile tile = world.tile(x, y);
        if(tile == null){
            return "拒绝建造 [" + blockName + "]: 坐标 (" + x + "," + y + ") 超出地图";
        }

        //2. pay (M1: direct deduction from core; real construction queue comes in M2)
        Building core = team.data().core();
        if(!state.rules.infiniteResources){
            float multiplier = state.rules.buildCostMultiplier;
            for(var stack : block.requirements){
                int cost = Math.max(1, Math.round(stack.amount * multiplier));
                if(core.items.get(stack.item) < cost){
                    return "拒绝建造 [" + blockName + "]: 资源不足,需要 " + stack.item.name + "x" + cost + " (现有 " + core.items.get(stack.item) + ")";
                }
            }
            for(var stack : block.requirements){
                int cost = Math.max(1, Math.round(stack.amount * multiplier));
                core.items.remove(stack.item, cost);
            }
        }

        //3. place instantly (M1 pipeline validation; M2 will route through the team build queue)
        tile.setBlock(block, team, rotation);
        return "成功建造 [" + blockName + "] 于 (" + x + "," + y + ")";
    }

    private String doChat(Jval action){
        String message = action.getString("message", "");
        if(message.isEmpty()) return "拒绝发言: 内容为空";
        if(message.length() > 200) message = message.substring(0, 200);
        String formatted = "[sky][AI指挥官][] " + message;
        Call.sendMessage(formatted);
        Log.info("@", formatted);
        return "已广播: " + message;
    }
}
