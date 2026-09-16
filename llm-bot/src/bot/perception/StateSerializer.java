package bot.perception;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import bot.BotConfig;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Category;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;

import static mindustry.Vars.*;

/**
 * Serializes the current game state into a compact JSON summary for the LLM.
 * All methods MUST be called on the main game thread.
 * The summary is deliberately lossy: token budget matters more than completeness.
 */
public class StateSerializer{
    private static final int ORE_SCAN_RADIUS = 40;

    private Team team;
    /** item -> {sumX, sumY, tileCount}, cached per world. */
    private final ObjectMap<Item, int[]> oreClusters = new ObjectMap<>();

    public void setTeam(Team team){
        this.team = team;
    }

    public boolean ready(){
        return team != null && state.isGame() && team.data() != null && team.data().hasCore();
    }

    /** Team display name for prompts. */
    public String teamName(){
        return team == null ? "unknown" : team.name;
    }

    /** Rescans ore clusters around the team's first core. Call on world load. */
    public void rescan(){
        oreClusters.clear();
        if(!ready()) return;

        Tile coreTile = team.data().core().tile;
        int cx = coreTile.x, cy = coreTile.y, r = ORE_SCAN_RADIUS;
        for(int x = Math.max(cx - r, 0); x <= Math.min(cx + r, world.width() - 1); x++){
            for(int y = Math.max(cy - r, 0); y <= Math.min(cy + r, world.height() - 1); y++){
                Item drop = world.tile(x, y).drop();
                if(drop != null){
                    int[] agg = oreClusters.get(drop, () -> new int[3]);
                    agg[0] += x;
                    agg[1] += y;
                    agg[2]++;
                }
            }
        }
        Log.info("[llm-bot] Ore scan complete: @ cluster(s) near core", oreClusters.size);
    }

    /** Builds the full state snapshot as a JSON string. */
    public String snapshot(Seq<String> lastResults){
        Jval root = Jval.newObject();

        //--- game level ---
        root.put("mode", modeName());
        root.put("wave", state.wave);
        root.put("nextWaveInSec", Math.round(state.wavetime / 60f));
        root.put("enemiesAlive", state.enemies);

        if(!ready()){
            root.put("note", "no core");
            return root.toString();
        }

        var data = team.data();
        var core = data.core();

        //--- core ---
        Jval coreJson = Jval.newObject();
        coreJson.put("x", core.tile.x);
        coreJson.put("y", core.tile.y);
        coreJson.put("hpPct", Math.round(core.health / core.maxHealth * 100));
        root.put("core", coreJson);

        //--- resources in core ---
        Jval items = Jval.newObject();
        for(Item item : content.items()){
            int amount = core.items.get(item);
            if(amount > 0) items.put(item.name, amount);
        }
        root.put("coreItems", items);

        //--- units ---
        Jval units = Jval.newObject();
        units.put("count", data.unitCount);
        units.put("cap", data.unitCap);
        Jval byType = Jval.newObject();
        if(data.typeCounts != null){
            for(UnitType type : content.units()){
                int count = type.id < data.typeCounts.length ? data.typeCounts[type.id] : 0;
                if(count > 0) byType.put(type.name, count);
            }
        }
        units.put("byType", byType);
        root.put("units", units);

        //--- buildings summary ---
        int turrets = 0, drills = 0, walls = 0, total = 0;
        for(ObjectMap.Entry<Block, Seq<Building>> entry : data.buildingTypes){
            Block block = entry.key;
            int count = entry.value.size;
            total += count;
            if(block.category == Category.turret){
                turrets += count;
            }else if(block instanceof Drill){
                drills += count;
            }
        }
        //walls: cheap separate pass
        for(var entry : data.buildingTypes.entries()){
            if(entry.key.name.contains("wall")) walls += entry.value.size;
        }
        Jval buildings = Jval.newObject();
        buildings.put("total", total);
        buildings.put("turrets", turrets);
        buildings.put("drills", drills);
        buildings.put("walls", walls);
        root.put("buildings", buildings);

        //--- ores near core ---
        Jval ores = Jval.newArray();
        for(ObjectMap.Entry<Item, int[]> entry : oreClusters){
            int[] agg = entry.value;
            if(agg[2] == 0) continue;
            Jval ore = Jval.newObject();
            ore.put("item", entry.key.name);
            ore.put("x", agg[0] / agg[2]);
            ore.put("y", agg[1] / agg[2]);
            ore.put("tiles", agg[2]);
            ores.add(ore);
        }
        root.put("oresNearCore", ores);

        //--- threats ---
        Jval threats = Jval.newObject();
        Unit nearest = null;
        float bestDist = Float.MAX_VALUE;
        int enemyCount = 0;
        for(Unit unit : Groups.unit){
            if(unit.team == team || unit.team == Team.derelict) continue;
            enemyCount++;
            float dist = unit.dst(core);
            if(dist < bestDist){
                bestDist = dist;
                nearest = unit;
            }
        }
        threats.put("enemyUnits", enemyCount);
        if(nearest != null){
            Jval near = Jval.newObject();
            near.put("type", nearest.type.name);
            near.put("x", nearest.tileX());
            near.put("y", nearest.tileY());
            near.put("distTiles", Math.round(bestDist / 8f));
            threats.put("nearestEnemy", near);
        }
        root.put("threats", threats);

        //--- last decision results (feedback loop) ---
        if(lastResults != null && lastResults.size > 0){
            Jval results = Jval.newArray();
            for(String result : lastResults){
                results.add(result);
            }
            root.put("lastActionResults", results);
        }

        return root.toString();
    }

    /** Builds a prompt-friendly catalog of the curated buildable blocks. */
    public String blockCatalog(){
        StringBuilder sb = new StringBuilder();
        for(String name : BotConfig.CATALOG){
            Block block = content.block(name);
            if(block == null || block.isHidden()) continue;

            sb.append(block.name).append(" | ").append(block.size).append("x").append(block.size).append("格 | 造价:");
            if(block.requirements == null || block.requirements.length == 0){
                sb.append(" 免费");
            }else{
                for(var stack : block.requirements){
                    sb.append(" ").append(stack.item.name).append("x").append(stack.amount);
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public String modeName(){
        if(state.rules.pvp) return "pvp";
        if(state.rules.attackMode) return "attack";
        if(state.rules.waves) return "survival";
        return "sandbox";
    }
}
