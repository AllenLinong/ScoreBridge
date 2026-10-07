# ScoreBridge

Velocity 代理端插件：当后端服务器的插件需要独占玩家屏幕上的计分板区域（Boss 战、小游戏、自定义 HUD 等）时，临时隐藏 [TAB](https://github.com/NEZNAMY/TAB) 插件的计分板，结束后自动恢复玩家原本的可见状态。

## 功能特性

- 通过插件消息通道对外提供协议 API，任何后端服务器插件（Paper / Spigot / Fabric 等，只要能发送插件消息）都可以调用
- **引用计数**：多个插件 / 多个功能可以同时对同一玩家持有占用，全部释放后才恢复原状
- 自动记录并恢复玩家原本的计分板可见状态（通过 TAB API 读取）
- 玩家断开连接时自动清理状态，不会残留
- 零 Java 依赖：调用方无需引入 ScoreBridge 的任何类，只依赖消息协议

## 环境要求

| 组件 | 要求 |
|------|------|
| Velocity | 3.x（编译基于 3.4.0-SNAPSHOT） |
| Java | 21+ |
| TAB 插件 | 需安装在 **Velocity 代理端**，ScoreBridge 通过它的 `btab scoreboard off/on <玩家> -s` 控制台命令切换可见性；未安装时隐藏不会生效，记录的原状态默认为"可见" |

## 安装

1. 编译：`./gradlew build`（或本机 `gradle build`），产物在 `build/libs/ScoreBridge-<版本>.jar`
2. 将 jar 放入 Velocity 的 `plugins/` 目录并重启代理
3. 确保 TAB 插件也已装在代理端

---

## 其他插件如何调用（API 说明）

ScoreBridge **没有导出 Java 类 API**，而是开放了一个基于 **插件消息（plugin message）通道** 的协议 API。后端服务器的插件通过玩家连接向代理发送消息，Velocity 拦截后由 ScoreBridge 处理，消息不会到达客户端。

### 协议规范

- **通道名**：`scorebridge:control`
- **方向**：后端服务器 → Velocity 代理（通过任意在线玩家的连接发送）
- **编码**：Java `DataOutputStream`（即 `writeUTF` 的 modified UTF-8 格式）

**消息体（按顺序共 3 个 UTF 字符串）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `op` | UTF 字符串 | `ACQUIRE`（申请占用）或 `RELEASE`（释放），不区分大小写 |
| `owner` | UTF 字符串 | 调用方自定义的占用者标识（如 `myplugin:bossfight`），不能为空白 |
| `uuid` | UTF 字符串 | 目标玩家的 UUID 字符串 |

### 协议语义

- `ACQUIRE`：把 `owner` 记入该玩家的占用集合。若是**第一个**占用者，先通过 TAB API 记下玩家当前的计分板可见状态，然后执行 `btab scoreboard off <玩家> -s` 隐藏计分板。同一 `owner` 重复 `ACQUIRE` 是幂等的。
- `RELEASE`：把 `owner` 移出占用集合。当集合为空（最后一个占用者释放）时，按之前记录的状态执行 `btab scoreboard on/off <玩家> -s` 恢复原状。
- 玩家断开代理时，其所有占用记录自动清空。
- 目标玩家不在线（代理上找不到该 UUID）或 `owner` 为空白时，消息被忽略。
- **请务必成对调用** `ACQUIRE` / `RELEASE`：未 `ACQUIRE` 直接 `RELEASE` 会把计分板强制设为可见。

### Paper / Spigot 后端插件示例

```java
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MyPlugin extends JavaPlugin {
    private static final String SB_CHANNEL = "scorebridge:control";
    private static final String OWNER_ID = "myplugin:bossfight";
    private static MyPlugin instance;

    @Override
    public void onEnable() {
        instance = this;
        // 注册发送通道（一次性）
        getServer().getMessenger().registerOutgoingPluginChannel(this, SB_CHANNEL);
    }

    /** Boss 战开始 / 打开自定义计分板时调用 */
    public static void hideTabScoreboard(Player player) {
        send(player, "ACQUIRE");
    }

    /** Boss 战结束 / 关闭自定义计分板时调用（务必保证调用，包括玩家退出等分支） */
    public static void restoreTabScoreboard(Player player) {
        send(player, "RELEASE");
    }

    private static void send(Player player, String op) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(op);                               // ACQUIRE / RELEASE
        out.writeUTF(OWNER_ID);                         // 占用者标识，非空
        out.writeUTF(player.getUniqueId().toString());  // 目标玩家 UUID
        player.sendPluginMessage(instance, SB_CHANNEL, out.toByteArray());
    }
}
```

配合事件使用的典型流程：

```java
// Boss 战开始
MyPlugin.hideTabScoreboard(player);

// Boss 战结束 / 玩家中途退出战斗 / 插件禁用时
MyPlugin.restoreTabScoreboard(player);
```

> 提示：消息通过「某个在线玩家」的连接送往代理即可，与消息体中的目标 UUID 可以不是同一人；最简单的做法就是用目标玩家本人的连接发送。

### 其他类型的后端

协议本身与平台无关。任何能向玩家连接发送自定义 payload（plugin message）的后端模组 / 插件都可以调用：按上表顺序写入三个 UTF 字符串，发往通道 `scorebridge:control` 即可。

## 注意事项

- ScoreBridge 不校验消息发送者身份，协议适用于**可信后端服务器**的网络环境。
- 隐藏 / 恢复动作通过代理端控制台命令 `btab scoreboard off/on <玩家> -s` 完成，因此要求 TAB 插件在代理端可用；读取原状态使用 TAB API（反射调用，TAB 缺失时默认视为"可见"）。

## 项目结构

```
src/main/java/com/scorebridge/ScoreBridgePlugin.java   # 全部逻辑（约 30 行）
src/main/resources/velocity-plugin.json                # 插件元数据
build.gradle.kts                                       # 构建（Java 21, Velocity API）
```

## English Summary

**ScoreBridge** is a Velocity proxy plugin that lets backend-server plugins temporarily hide the TAB plugin's scoreboard for a player (e.g., during boss fights or minigames) and restore the original visibility afterwards.

It exposes a **plugin-messaging API** (no Java classes needed): send a plugin message on channel `scorebridge:control` with three UTF strings — `op` (`ACQUIRE`/`RELEASE`), `owner` (your plugin's non-blank identifier), and the target player's `uuid`. Hides are reference-counted per player; the original state is restored when the last owner releases (or on disconnect). Requires TAB on the proxy; toggling is done via `btab scoreboard off/on <player> -s`. See the example above for Paper/Spigot usage.
