# Mindustry 源码阅读清单(v8)

> 本清单基于 v8 release 分支整理,所有路径/行号/机制均已对照源码验证。
> 配套阅读:仓库根目录 `README.md`、`CONTRIBUTING.md`(代码风格);引擎源码在同级 `../Arc`。

## 如何使用这份清单

- 按 **阶段顺序** 读,每阶段末尾有「自测题」,答得上来再进下一阶段
- 标 ⭐ 的是核心中的核心,标 ⏭ 的首轮可跳过
- 大文件(>2000 行)首轮只读类头注释 + 公开方法签名,别逐行啃
- 推荐在 IDEA 中配合 Debug 运行(见 §0.3 断点清单),读代码不如跑代码

---

## 0. 准备工作

### 0.1 环境

| 项 | 值 |
| --- | --- |
| JDK | 17(`~/Library/Java/JavaVirtualMachines/azul-17.0.19`) |
| 引擎源码 | 同级 `../Arc`(jdtls/IDEA 解析 arc.* 类要靠它) |
| 运行 | IDEA Gradle 任务 `desktop:run`(桌面)/ `server:run`(无头服务器) |
| 调试 | Application 配置:主类 `mindustry.desktop.DesktopLauncher`,工作目录 `core/assets` |

### 0.2 先跑起来一次

`server:run` → 控制台 `host` 开一局 → 观察日志。之后所有阅读都对照这局游戏。

### 0.3 全局断点清单(配合各阶段使用)

| 断点位置 | 用途 |
| --- | --- |
| `Logic.update()` | 游戏主循环心跳 |
| `Logic.play()` | 一局开始时发生了什么 |
| `World.loadMap/runRandomMap` | 地图如何变成 Tiles |
| `Block.buildConfiguration` | UI 上配置方块 |
| `EntityProcess.process` | 编译期:实体类如何被合成 |
| `net` 任一 `@Remote` 方法 | 网络同步动作 |

---

## 1. 全局地图(30 分钟)

### 1.1 模块(模块名:定位)

| 模块 | 文件数 | 定位 |
| --- | --- | --- |
| `core/` | 823 | 全部游戏逻辑(客户端+服务端共享) |
| `desktop/` | 7 | 桌面启动器(SDL/Steam 集成) |
| `server/` | 2 | 无头服务器(`ServerLauncher`+`ServerControl`) |
| `android/` `ios/` | 2/1 | 移动端启动器 |
| `annotations/` | 18 | **编译期注解处理器**(实体合成/网络代码生成) |
| `tools/` | 8 | 开发工具(精灵打包等) |
| `tests/` | 10 | 测试 |
| `llm-bot/` | 7 | 我们开发的 LLM AI 玩家插件(见 §5) |

### 1.2 core 内各包(按规模排序,§4 的深潜对象)

| 包 | 文件数 | 一句话 |
| --- | --- | --- |
| `mindustry.world` | 258 | 方块与建造:Block 基类 + 14 类功能方块 |
| `mindustry.entities` | 139 | 实体:ECS 组件 + 碰撞/伤害/子弹 |
| `mindustry.ui` | 84 | 界面(arc Scene) |
| `mindustry.graphics` | 40 | 渲染 |
| `mindustry.logic` | 36 | 游戏内逻辑(mlog 虚拟机) |
| `mindustry.type` | 34 | 内容类型:UnitType/Item/Liquid/Weather |
| `mindustry.ai` | 30 | 寻路 + 单位 AI + 基地 AI |
| `mindustry.io` | 28 | 存档/类型序列化 |
| `mindustry.editor` | 28 | 地图编辑器 |
| `mindustry.mod` | 24 | Mod/插件系统 |
| `mindustry.game` | 21 | 对局状态:Rules/Team/EventType/Schematics |
| `mindustry.net` | 15 | 网络传输层 |
| `mindustry.content` | 15 | 全部内容定义(Blocks 7037 行!) |
| `mindustry.core` | 13 | 生命周期:Logic/World/GameState/Net* |
| 其他 | ~30 | input/audio/async/service/maps |

---

## 2. 阶段一:启动与主循环(半天)

**目标:搞清"从双击图标到看见游戏"以及"每一帧发生了什么"。**

### 2.1 阅读顺序

1. ⭐ `desktop/src/mindustry/desktop/DesktopLauncher.java` `main()`
   → 只看主干:SDL 初始化 → `new ClientLauncher()` 交给 arc 后端
2. ⭐ `core/src/mindustry/ClientLauncher.java`
   - `setup()`:GL 检查、日志、mod 加载入口
   - L177-182 **模块注册顺序**(背下来):

     ```java
     add(logic = new Logic());      // 游戏逻辑
     add(control = new Control());   // 输入/存档/战役
     add(renderer = new Renderer()); // 渲染
     add(ui = new UI());             // 界面
     add(netServer = new NetServer());
     add(netClient = new NetClient());
     ```

   - 每个模块都是 arc 的 `ApplicationListener`,`ApplicationCore` 统一按序调 `init()`/`update()`(引擎源:`../Arc/arc-core/src/arc/ApplicationCore.java`,30 行,值得全读)
3. ⭐ `core/src/mindustry/core/GameState.java`(103 行,全读)
   → 对局的全部顶层状态:wave/tick/rules/teams
4. ⭐ `core/src/mindustry/core/Logic.java` 的三个方法:
   - `update()`(L509 附近 `Events.fire(Trigger.update)`)— 主循环
   - `play()` — 开局:发资源、起事件
   - `wave()` / 敌人生成路径
5. `core/src/mindustry/server/ServerControl.java` — 无头服务器如何 host 一局(对照你跑过的 server:run)
6. ⏭ `Vars.java` — 全局变量大杂烩,当字典查,别通读

### 2.2 自测题

- 一帧之内,六个模块谁先谁后 update?游戏逻辑和网络谁驱动谁?
- `state.isGame()` 和 `state.isPlaying()` 差在哪?
- 无头服务器没有 Renderer,主循环缺了渲染还剩什么?

---

## 3. 阶段二:三大核心机制(2~3 天)

### 3.1 机制一:注解驱动的代码生成 ⭐⭐(本项目的灵魂)

**先读 `annotations/` 模块,再回来读实体,否则 entities 包读不懂。**

1. `annotations/src/main/java/mindustry/annotations/Annotations.java`
   → 注解定义目录:@EntityDef/@Remote/@Struct/@Register 等
2. ⭐ `annotations/.../entity/EntityProcess.java` — 核心处理器:
   - 输入:`entities/comp/` 下的组件类(如 `BulletComp`,带 `@EntityDef`)
   - 输出:`core/build/generated/.../mindustry/gen/` 下的合成实体类
   - **去 build/generated 目录亲眼看一个生成的 `Bullet.java`**,对照 `BulletComp` 理解"组件如何被拍平进实体"
3. `annotations/.../remote/RemoteProcess.java` + `CallGenerator.java`
   → `@Remote` 注解的方法(散布在 NetClient/NetServer)如何生成 `gen/Call.java` 的 RPC 代理
   → 打开生成的 `Call.java` 看 `buildTile` 一类方法的长相
4. ⏭ `StructProcess`(紧凑序列化)、`AssetsProcess`

**理解检验**:为什么 `mindustry.gen` 包在源码树里不存在、却在 IDEA 里能跳转?

### 3.2 机制二:内容系统 ⭐

1. ⭐ `core/src/mindustry/ctype/Content.java` + `ContentType.java`(全读,很短)
2. ⭐ `core/src/mindustry/core/ContentLoader.java`
   - `createBaseContent()` 的**加载顺序**(Items→StatusEffects→Liquids→Bullets→UnitTypes→Blocks→…)为什么 UnitTypes 在 Blocks 前、TechTree 在最后?(内容间引用决定的)
3. `core/src/mindustry/content/Items.java`(100 行级,体会声明式风格)
4. ⭐ `core/src/mindustry/content/Blocks.java`(7037 行)
   → **不要通读**。精读 3 个代表:`mechanical-drill`(生产类)、`duo`(炮塔)、`conveyor`(运输类);再浏览 `copperWall`/`router`。重点看构造块里的链式配置:`requirements(...)`/`health(...)`/`size=...` 这些都写进了 Block 基类的哪些字段?
5. `core/src/mindustry/type/UnitType.java`(2065 行)— 读字段区+`create()`,理解 `controller` 字段(L281:`Prov<UnitController>`)——这是 AI 的挂载点,和 llm-bot 直接相关

### 3.3 机制三:世界与方块 ⭐

1. ⭐ `core/src/mindustry/world/Tile.java` — 一格世界里有什么:`floor`/`overlay`/`block`/`build` 四层
2. ⭐ `core/src/mindustry/world/Block.java`(1709 行)
   → 只读字段区+生命周期方法:`init()`/`newBuild()`/`canPlaceOn`/`canReplace`
3. ⭐ `core/src/mindustry/world/blocks/Block` 子类骨架,读一个最简单的:
   `world/blocks/defense/Wall.java`(几十行)+ 一个中等的:`distribution/Conveyor.java`
4. `mindustry/gen/Building.java`(生成的)+ `world/BuildingComp.java`(2317 行)
   → Building 生命周期:`updateTile()`/`handleItem()`/`onDestroyed()`;为什么 Building 也是合成的 ECS 实体?
5. ⭐ `core/src/mindustry/world/Build.java` — 建造的入口 API:
   `beginPlace/beginBreak/validPlace`(llm-bot 的 ActionExecutor 就调的它)

### 3.4 自测题

- 一个 `Tile` 上同时有 floor、ore、conveyor、单位——各存在哪个字段/系统里?
- `Block` 和 `Building` 的关系?一个钻头方块实例的"每帧逻辑"写在哪个类?
- `Call.buildTile` 从客户端发出后,如何同步到服务器和其他客户端?

---

## 4. 阶段三:八大子系统深潜(1~2 周,按需选读)

按你后续的开发方向选 2~3 个精读,其余浏览。

### 4.1 AI 系统(与 llm-bot 最相关)⭐⭐

| 顺序 | 文件 | 看什么 |
| --- | --- | --- |
| 1 | `ai/Pathfinder.java` | flow-field 寻路:每 tick 分片计算 |
| 2 | `ai/ControlPathfinder.java`(1658 行) | A* 前台/后台线程协作 |
| 3 | `entities/units/AIController.java` | 单位 AI 基类:`updateUnit()` 模板方法(updateVisuals→updateTargeting→updateMovement) |
| 4 | `ai/types/GroundAI.java` | 最简单的完整 AI:追打最近敌人 |
| 5 | `ai/types/CommandAI.java` | 玩家指挥的 AI:`commandPosition/commandTarget`(llm-bot M3 会用) |
| 6 | ⭐ `ai/RtsAI.java` | **团队级指挥 AI**(llm-bot 的挂载点):`update()`→`assignSquads()` 分队→`handleSquad()` 决策;`Logic.java:569` 处每 2 秒被调 |
| 7 | `ai/BaseBuilderAI.java` | 基地建造 AI:从 `TeamData.plans` 队列取活干活 |
| 8 | `ai/BaseRegistry.java`+`assets/baseparts/` | AI 的"蓝图库"(.msch 格式) |

### 4.2 网络与多人 ⭐(做联机机器人必读)

1. `net/Net.java` — 传输抽象(UDP/TCP/Steam 可换后端)
2. `core/NetServer.java`(1488 行)— 服务器权威:
   - 快照同步 `sync()`
   - 玩家验证/管理
3. `core/NetClient.java` — 客户端:连接、加入流程(含 `@Remote` 方法示例)
4. `io/TypeIO.java`(1469 行)⏭ — 自定义类型如何序列化(字典式查阅)
5. 回看 3.1 的 `RemoteProcess`:完整闭环

### 4.3 逻辑系统 mlog(游戏内编程)

1. `logic/LExecutor.java`(2423 行)⏭ — 虚拟机:只读 `run()` 主循环和指令分发表
2. `logic/LParser.java` — mlog 文本如何编译成指令
3. `logic/LCanvas.java` — 可视化编辑器(UI 层,快读)
4. 有趣的点:`world/blocks/logic/LogicBlock.java` — 玩家放一个"处理器"方块,里面跑字节码

### 4.4 实体与战斗

1. `entities/comp/` 全目录 — 组件目录学:`PosComp/HealthComp/BulletComp/UnitComp...` 挑 5 个读
2. `entities/Units.java` — 实体查询门面(空间索引)
3. `entities/Damage.java` — 伤害结算/链式爆炸
4. `entities/bullet/` — 弹药类型体系
5. `entities/comp/BuilderComp` + `world/BuildPlan` — 单位怎么执行建造计划(和 3.3 的 Build.java 对上)

### 4.5 战役与存档

1. `game/Rules.java` — 规则即配置:所有可调项(pvp/rtsAi/buildAi 都在这,注意 L43-45)
2. `game/Schematics.java` — 蓝图系统:`toPlans()`(llm-bot M3 用)
3. `game/Saves.java` + `io/SaveVersion.java` — 存档版本迁移怎么做(教科书级)
4. `maps/generators/` — 地图生成器;`maps/planet/` — 战役星球

### 4.6 Mod 系统(写插件必读)

1. `mod/Mod.java` — 生命周期:init/loadContent/registerServerCommands(全读,50 行)
2. `mod/Mods.java`(1498 行)— 加载管线:扫描 jar→mod.hjson→ClassLoader;`ModMeta`(L1392)
3. `mod/Plugin.java` — 隐藏型 mod(服务端插件,llm-bot 的基类)
4. `mod/ContentParser.java` ⏭ — JSON 内容动态解析(给 JS/JSON mod 用)

### 4.7 渲染(快读)

1. `core/Renderer.java` — 帧流程:camera→layers
2. `graphics/Drawf.java` — 方块绘制工具
3. `world/draw/DrawBlock.java` — 方块渲染器分离设计
4. ⏭ `graphics/g3d/` — 战役星球 3D

### 4.8 输入与 UI(快读)

1. `input/InputHandler.java`(2552 行)⏭ — 只读 `buildPlacementChecks`/选区逻辑
2. `ui/fragments/` — HUD 组装;`ui/dialogs/` 对话框库

---

## 5. 阶段四:llm-bot 插件贯通阅读(半天)

把我们写的 7 个文件和上述机制对上:

| llm-bot 文件 | 挂接的游戏机制 | 回看 |
| --- | --- | --- |
| `LlmBotPlugin` | Mod 生命周期 + `arc.Events`(WorldLoad/Wave/Reset) | §4.6 |
| `StateSerializer` | `TeamData`(cores/items/typeCounts/buildingTypes)、`Groups.unit` | §3.3/§4.4 |
| `ActionExecutor` | `Build.validPlace`、`Tile.setBlock`、`content.block(name)` | §3.3 |
| `AgentLoop` | `Core.app.post` 主线程调度(参考 `AsyncCore` 的并发模式) | §4.1 |
| `BotConfig` | `Core.settings.getDataDirectory()`、mod 配置目录 | §4.6 |

---

## 6. 术语表

| 术语 | 含义 |
| --- | --- |
| **arc** | 作者自维护的游戏库(libGDX fork),`arc.*` 命名空间 |
| **Content** | 一切可注册内容(方块/物品/单位/液体…)的基类 |
| **Tile** | 一个格子:floor+overlay+block+build 四层 |
| **Building / build** | 方块的运行时实例(建筑),ECS 实体 |
| **Unit** | 单位(也是 ECS 实体) |
| **Team / TeamData** | 队伍 / 队伍运行时数据(核心、单位表、建造队列 plans) |
| **mlog** | 游戏内逻辑处理器跑的汇编式语言 |
| **Schematic (.msch)** | 建造蓝图(可导出/导入/贴地) |
| **waves** | 生存模式的敌人生成波次,`waveTeam` 为敌方 |
| **derelict** | 无主/废弃状态(中立建筑) |
| **Erekir / Serpulo** | 战役两大星球(两套科技树) |
| **RtsAI / buildAi / rtsAi** | 团队指挥 AI / 建造 AI 开关(规则项,可按队伍开) |

---

## 7. 建议节奏

| 周 | 内容 |
| --- | --- |
| D1 | §1 地图 + §2 启动链路(边跑边读) |
| D2-4 | §3 三大机制(重点 ECS 代码生成) |
| D5-7 | §4.1 AI + §4.2 网络(llm-bot 主线需要) |
| 第 2 周 | 按方向自选 2 个子系统精读 + §5 llm-bot 贯通 |
| 之后 | 遇到问题回本清单按图索骥 |

> 记住心智模型:**"注解生成样板 + 声明式内容 + ECS 实体 + 事件驱动"**——这四个词能解释这个项目 80% 的设计。
