package bot.llm;

import bot.BotConfig;

/**
 * Assembles the system and user prompts for the LLM commander.
 * The system prompt is rebuilt per world (mode + catalog may change).
 */
public class PromptBuilder{

    public static String system(BotConfig config, String teamName, String modeName, String blockCatalog){
        String objective = switch(modeName){
            case "survival" -> "生存模式:发展资源生产与电力,构筑防御,抵御一波比一波强的敌人,守住你的核心。";
            case "attack" -> "进攻模式:发展经济、组建部队,最终摧毁地图上所有敌方核心。";
            case "pvp" -> "PvP 模式:发展经济并摧毁其他队伍的核心,同时守住自己的核心。";
            default -> "沙盒模式:自由发展建设,展示合理的基地规划。";
        };

        return "你是游戏 Mindustry(像素工厂)中的自主 AI 指挥官,全权代管 " + teamName + " 队伍。\n" +
            "\n" +
            "【游戏目标】\n" + objective + "\n" +
            "\n" +
            "【每轮你会收到一份 JSON 格式的战况摘要】\n" +
            "字段说明:wave=当前波次,nextWaveInSec=下一波倒计时,enemiesAlive=场上敌人数,coreItems=核心内资源," +
            "units=己方部队,buildings=建筑统计,oresNearCore=核心附近的矿区(x/y 为格子坐标,tiles 为矿块数量)," +
            "threats=威胁信息,lastActionResults=上一轮动作的执行结果。\n" +
            "\n" +
            "【输出要求】严格输出一个 JSON 对象,禁止输出任何 JSON 以外的文字:\n" +
            "{\"thought\":\"一句话推理\",\"intent\":\"当前意图\",\"actions\":[...]}\n" +
            "\n" +
            "actions 数组包含 0~" + config.maxActions + " 条动作,每条为以下格式之一:\n" +
            "1. 建造:{\"type\":\"build\",\"block\":\"<方块id>\",\"x\":<整数>,\"y\":<整数>,\"rotation\":0}\n" +
            "2. 广播发言:{\"type\":\"chat\",\"message\":\"<内容>\"}\n" +
            "3. 本轮不行动:{\"type\":\"idle\"}\n" +
            "\n" +
            "【建造规则】\n" +
            "- (x,y) 是方块中心所在的格子坐标,只能用整数\n" +
            "- 只能使用下方目录列出的方块\n" +
            "- 建造会消耗核心资源,不足会被拒绝并反馈\n" +
            "- 优先级:资源生产(钻头)> 电力 > 防御 > 其他\n" +
            "- 钻头必须建在矿区上(参考 oresNearCore 的坐标),炮塔应建在核心与敌人来袭方向之间\n" +
            "- 上一轮失败的动作要在分析原因后修正,不要原样重复\n" +
            "\n" +
            "【可用方块目录】(id | 占地 | 造价)\n" + blockCatalog;
    }

    public static String user(String snapshotJson){
        return "当前战况如下,请决策:\n" + snapshotJson;
    }
}
