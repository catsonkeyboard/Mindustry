package bot.agent;

import arc.Core;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import bot.BotConfig;
import bot.actions.ActionExecutor;
import bot.llm.LlmClient;
import bot.llm.PromptBuilder;
import bot.perception.StateSerializer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static mindustry.Vars.state;

/**
 * The autonomous decision loop. Runs on its own daemon thread and never blocks the game:
 * <ol>
 *   <li>polls every 2s; a decision is due every {@code intervalSec} or when flagged urgent</li>
 *   <li>asks the main thread (via Core.app.post) to serialize the game state</li>
 *   <li>calls the LLM on this thread (blocking HTTP)</li>
 *   <li>parses the response and posts validated actions back to the main thread</li>
 *   <li>records results as feedback for the next round</li>
 * </ol>
 */
public class AgentLoop implements Runnable{
    private static final String TAG = "[llm-bot]";

    private final BotConfig config;
    private final LlmClient client;
    private final StateSerializer serializer;
    private final ActionExecutor executor;

    private final Seq<String> lastResults = new Seq<>();
    private volatile boolean active = false;
    private volatile boolean urgent = false;
    private volatile long lastDecisionTime = 0;
    private Thread thread;

    public AgentLoop(BotConfig config, LlmClient client, StateSerializer serializer, ActionExecutor executor){
        this.config = config;
        this.client = client;
        this.serializer = serializer;
        this.executor = executor;
    }

    public void start(){
        if(thread != null) return;
        thread = new Thread(this, "llm-bot-agent");
        thread.setDaemon(true);
        thread.start();
        Log.info("@ agent loop started (interval=@s, model=@)", TAG, config.intervalSec, config.model);
    }

    public void activate(){
        active = true;
    }

    public void deactivate(){
        active = false;
    }

    public void urgent(){
        urgent = true;
    }

    public boolean isActive(){
        return active;
    }

    public String status(){
        long since = (System.currentTimeMillis() - lastDecisionTime) / 1000;
        return "active=" + active + ", teamReady=" + serializer.ready() +
            ", interval=" + config.intervalSec + "s, lastDecision=" + since + "s ago, model=" + config.model +
            ", feedbackEntries=" + lastResults.size;
    }

    @Override
    public void run(){
        while(true){
            try{
                Thread.sleep(2000);
                if(!active || !state.isGame()) continue;

                boolean due = System.currentTimeMillis() - lastDecisionTime >= config.intervalSec * 1000L;
                if(!due && !urgent) continue;
                urgent = false;

                decideOnce();
            }catch(InterruptedException e){
                return;
            }catch(Throwable t){
                //never let the loop die
                Log.err(TAG, "agent loop error", t);
            }
        }
    }

    private void decideOnce() throws InterruptedException{
        lastDecisionTime = System.currentTimeMillis();

        //1. snapshot on the main thread
        String[] holder = new String[1];
        if(!postToMainThread(() -> {
            try{
                if(serializer.ready()) holder[0] = serializer.snapshot(lastResults);
            }catch(Throwable t){
                Log.err(TAG, "snapshot failed", t);
            }
        })){
            return;
        }
        String snapshot = holder[0];
        if(snapshot == null) return; //not ready (no core etc.)

        //2. LLM call on this thread
        String raw;
        try{
            String system = PromptBuilder.system(config, serializer.teamName(), serializer.modeName(), serializer.blockCatalog());
            raw = client.chat(system, PromptBuilder.user(snapshot));
        }catch(Exception e){
            Log.err(TAG, "LLM 调用失败: @", e.getMessage());
            lastResults.clear();
            lastResults.add("LLM调用失败: " + e.getMessage());
            return;
        }

        //3. parse the response
        Jval parsed;
        try{
            parsed = LlmClient.extractJson(raw);
        }catch(Exception e){
            Log.err(TAG, "LLM 输出解析失败", e);
            lastResults.clear();
            lastResults.add("LLM输出不是合法JSON,已被丢弃");
            return;
        }
        String thought = parsed.getString("thought", "");
        String intent = parsed.getString("intent", "");

        //4. execute actions on the main thread
        AtomicReference<Seq<String>> resultRef = new AtomicReference<>();
        postToMainThread(() -> {
            try{
                resultRef.set(executor.execute(parsed.get("actions")));
            }catch(Throwable t){
                Log.err(TAG, "action execution crashed", t);
            }
        });

        //5. record feedback (best effort: give the main thread a moment)
        Thread.sleep(500);
        Seq<String> results = resultRef.get();
        if(results == null){
            results = Seq.with("动作执行超时/异常");
        }

        lastResults.clear();
        for(String result : results){
            lastResults.add(result);
        }

        Log.info("@ 决策完成 | 意图: @ | 思考: @ | 结果: @", TAG, intent, thought, results.toString(", "));
    }

    /** Posts a task to the main thread and waits up to 5s. @return false on timeout. */
    private boolean postToMainThread(Runnable task) throws InterruptedException{
        CountDownLatch latch = new CountDownLatch(1);
        Core.app.post(() -> {
            try{
                task.run();
            }finally{
                latch.countDown();
            }
        });
        return latch.await(5, TimeUnit.SECONDS);
    }
}
