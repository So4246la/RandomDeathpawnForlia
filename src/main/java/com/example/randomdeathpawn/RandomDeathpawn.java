package com.example.randomdeathpawn;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * RandomDeathpawn Ver1.3
 * - 一週間ごとに全員のライフを初期化
 * - ネザーやエンドで死亡した場合はオーバーワールドにリスポーン
 * - ライフ0で観戦モード＋一定時間後に自動復帰
 * - 初参加＆リスポーンはランダムスポーン (ベッド・アンカー問わず常にランダム)
 * - /addlives [数値] でライフを加算、引数なしならライフを初期値に戻し、観戦中なら即復帰
 * - /checklives で現在のライフを確認
 * - ライフリセットまでの残り時間を「X日Y時間」形式で1時間ごとにアナウンス
 * - 1時間未満になったら「残り30分、15分、5分、1分」と細かく通知
 *
 * ★追加修正★
 * ・ベッドリスポーンを完全に上書き (EventPriority.HIGHEST で確実にランダムスポーン)
 * ・プレイヤーライフ数と次回リセット時刻を livedata.yml に保存 (サーバー再起動後も保持)
 * ・ライフが負数にならないように (if (lives < 0) lives = 0;)
 * ・観戦モード復活予定時刻もファイルに保存し、再起動後も維持
 * ・再起動後に観戦モードが解除されるのを防ぐため、ログイン時にチェック
 * ・ライフ0のプレイヤーがあと何分で復活可能かを表示する /checkrevive コマンドを追加
 * ・/addlives コマンドで他人のライフを操作できる機能を追加
 */



public class RandomDeathpawn extends JavaPlugin implements Listener {

private static RandomDeathpawn plugin;

    // コンフィグから読み取るデフォルトライフ・復活時間・スポーン範囲
    private int defaultDeathLimit;
    private long defaultRevivalTimeHours;
    private int spawnRange;

    // サーバーのメインワールド名
    private final String mainWorldName = "world";

    // プレイヤーごとの残りライフ
    private final Map<UUID, Integer> remainingLives = new HashMap<>();
    // 観戦モードからの復活予定時刻 (ミリ秒)
    private final Map<UUID, Long> revivalTimestamps = new HashMap<>();

    // 次回リセット時刻 (ミリ秒)
    private long nextResetTime;
    // 既にアナウンスした「残り時間」を記録して重複通知を防ぐ
    private final Set<Long> announcedTimes = new HashSet<>();

    // ランダムやタイマーなどで使用
    private final Random random = new Random();

    // ★追加★ livedata.yml 用
    private File liveDataFile;
    private YamlConfiguration liveDataYaml;

  @Override
public void onEnable() {
    saveDefaultConfig();
    FileConfiguration config = getConfig();
    plugin = this; 

    defaultDeathLimit = config.getInt("deathLimit", 3);
    defaultRevivalTimeHours = config.getLong("revivalTimeHours", 1);
    spawnRange = config.getInt("spawnRange", 10000);
    
    loadData();

    getServer().getPluginManager().registerEvents(this, this);

    startRevivalCheckTask();
    scheduleWeeklyReset();
    startWeeklyResetAnnouncementTask();

    getLogger().info("RandomDeathpawn Ver1.3 が有効になりました！");

    }



    @Override
    public void onDisable() {
        // ★追加★ livedata.yml にデータを保存
        saveData();

        getLogger().info("RandomDeathpawn Ver1.3 が無効になりました！");
    }

    // ==================================================
    //          livedata.yml の読み書き処理
    // ==================================================
    private void loadData() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        liveDataFile = new File(getDataFolder(), "livedata.yml");
        if (!liveDataFile.exists()) {
            try {
                liveDataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        liveDataYaml = YamlConfiguration.loadConfiguration(liveDataFile);

        // 次回リセット時刻
        nextResetTime = liveDataYaml.getLong("nextResetTime", 0L);

        // 未設定 or 過去なら「今から1週間後」
        long now = System.currentTimeMillis();
        if (nextResetTime <= now) {
            nextResetTime = now + TimeUnit.DAYS.toMillis(7);
        }

        // プレイヤーライフを読み込み
        if (liveDataYaml.isConfigurationSection("lives")) {
            for (String uuidStr : liveDataYaml.getConfigurationSection("lives").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    int lives = liveDataYaml.getInt("lives." + uuidStr, defaultDeathLimit);
                    if (lives < 0) {
                        lives = 0;
                    }
                    remainingLives.put(uuid, lives);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("[RandomDeathpawn] Invalid UUID in livedata.yml: " + uuidStr);
                }
            }
        }

        // 観戦モード復活予定時刻を読み込み
        if (liveDataYaml.isConfigurationSection("revivalTimestamps")) {
            for (String uuidStr : liveDataYaml.getConfigurationSection("revivalTimestamps").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    long revivalTime = liveDataYaml.getLong("revivalTimestamps." + uuidStr, 0L);
                    revivalTimestamps.put(uuid, revivalTime);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("[RandomDeathpawn] Invalid UUID in livedata.yml: " + uuidStr);
                }
            }
    }
}

    private void saveData() {
    if (liveDataYaml == null || liveDataFile == null) {
        return;
    }

    liveDataYaml.set("nextResetTime", nextResetTime);

    for (Map.Entry<UUID, Integer> entry : remainingLives.entrySet()) {
        liveDataYaml.set("lives." + entry.getKey().toString(), entry.getValue());
    }

    for (Map.Entry<UUID, Long> entry : revivalTimestamps.entrySet()) {
        liveDataYaml.set("revivalTimestamps." + entry.getKey().toString(), entry.getValue());
    }


    try {
        liveDataYaml.save(liveDataFile);
    } catch (IOException e) {
        e.printStackTrace();
    }
}

    // ==================================================
    //         イベントハンドラ ＆ 固有ロジック
    // ==================================================



@EventHandler
public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    UUID uuid = player.getUniqueId();

    if (!player.hasPlayedBefore()) {
        Location spawn = findRandomSafeLocation(getMainWorld());
        player.teleport(spawn);

        String coordMessage = String.format("§e初参加のプレイヤー %s が [x: %d, y: %d, z: %d] にスポーンしました！",
                player.getName(),
                spawn.getBlockX(),
                spawn.getBlockY(),
                spawn.getBlockZ());

        Bukkit.broadcastMessage(coordMessage);

        Bukkit.getRegionScheduler().runDelayed(plugin, spawn, delayedTask -> {
            player.sendMessage(coordMessage);
            player.sendMessage(String.format("§a初参加なのでランダムスポーン地点 [x: %d, y: %d, z: %d] へテレポートしました！",
                    spawn.getBlockX(),
                    spawn.getBlockY(),
                    spawn.getBlockZ()));
        }, 40L);

        remainingLives.put(uuid, defaultDeathLimit);
        saveData();
    }

    remainingLives.putIfAbsent(uuid, defaultDeathLimit);
    Bukkit.getRegionScheduler().runDelayed(plugin, player.getLocation(), delayedTask -> {
        checkAndSetSpectatorIfNeeded(player);
    }, 20L);
}

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();

        int lives = remainingLives.getOrDefault(uuid, defaultDeathLimit);
        lives--;
        if (lives < 0) {
            lives = 0;
        }
        remainingLives.put(uuid, lives);
        saveData();

        String defaultMessage = event.getDeathMessage();
        if (defaultMessage == null) {
            defaultMessage = player.getName() + " died";
        }
        int displayLives = Math.max(lives, 0);
        String newMessage = defaultMessage + " §7[残りライフ: " + displayLives + "]";
        event.setDeathMessage(newMessage);

        if (lives <= 0) {
            player.setGameMode(GameMode.SPECTATOR);
            player.sendMessage("§cライフが0になりました。一定時間観戦モードになります。");
            player.sendMessage("§c復活までの時間は「/checkrevive」でいつでも確認できます！");

            long revivalDelayMillis = TimeUnit.HOURS.toMillis(defaultRevivalTimeHours);
            revivalTimestamps.put(uuid, System.currentTimeMillis() + revivalDelayMillis);
            saveData();
        }
    }

@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
public void onPlayerRespawn(PlayerRespawnEvent event) {
    World mainWorld = getMainWorld();

    // イベント処理内で即座に同期処理を実行（重要）
    Location spawn = findRandomSafeLocation(mainWorld);
    event.setRespawnLocation(spawn);

    String coordMessage = String.format("§b%s が [x: %d, y: %d, z: %d] にリスポーンしました！",
            event.getPlayer().getName(),
            spawn.getBlockX(),
            spawn.getBlockY(),
            spawn.getBlockZ());

    Bukkit.broadcastMessage(coordMessage);

    // 遅延して個別通知だけを行う
    Bukkit.getRegionScheduler().runDelayed(plugin, spawn, delayedTask -> {
        event.getPlayer().sendMessage(coordMessage);
    }, 40L);
}


private void scheduleWeeklyReset() {
    long oneWeekMillis = TimeUnit.DAYS.toMillis(7);
    long now = System.currentTimeMillis();
    long delayMillis = nextResetTime - now;
    if (delayMillis < 0) delayMillis = 0;

    Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
        for (UUID uuid : remainingLives.keySet()) {
            remainingLives.put(uuid, defaultDeathLimit);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) {
                releasePlayer(p);
            }
        }
        getLogger().info("一週間が経過したため、全員のライフを初期化しました。");
        Bukkit.broadcastMessage("§a一週間が経過したため、全員のライフを初期化しました。");

        nextResetTime = System.currentTimeMillis() + oneWeekMillis;
        announcedTimes.clear();
        saveData();

        scheduleWeeklyReset();
    }, delayMillis / 50);
}

private void startWeeklyResetAnnouncementTask() {
    Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
        long remainingMillis = nextResetTime - System.currentTimeMillis();
        if (remainingMillis <= 0) return;

        long days = TimeUnit.MILLISECONDS.toDays(remainingMillis);
        long hours = TimeUnit.MILLISECONDS.toHours(remainingMillis) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMillis) % 60;

        long timeKey = days * 24 + hours;

        if (timeKey > 0 && !announcedTimes.contains(timeKey)) {
            Bukkit.broadcastMessage(
                String.format("§6ライフリセットまで残り §e%d日 %d時間 §6です！ /checklives で現在のライフを確認できます。", days, hours)
            );
            Bukkit.broadcastMessage("§7(ライフ0の方は「/checkrevive」で復活までの時間を確認できます！)");
            announcedTimes.add(timeKey);
        }

        if (days == 0 && hours == 0) {
            if (minutes == 30 || minutes == 15 || minutes == 5 || minutes == 1) {
                Bukkit.broadcastMessage("§6ライフリセットまで残り §e" + minutes + "分 §6です！");
                Bukkit.broadcastMessage("§7(ライフ0の方は「/checkrevive」で復活までの時間を確認できます！)");
            }
        }
    }, 1200L, 1200L);
}
private void startRevivalCheckTask() {
    Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            Long revivalTime = revivalTimestamps.get(uuid);
            if (revivalTime != null && revivalTime <= now && p.getGameMode() == GameMode.SPECTATOR) {
                remainingLives.put(uuid, defaultDeathLimit);
                revivalTimestamps.remove(uuid);
                Bukkit.getRegionScheduler().run(plugin, p.getLocation(), innerTask -> releasePlayer(p));
                saveData();
            }
        }
    }, 20L, 20L);
}




private void releasePlayer(Player player) {
    World mainWorld = getMainWorld();

    Bukkit.getRegionScheduler().run(plugin, mainWorld.getSpawnLocation(), task -> {
        Location spawnLocation = findRandomSafeLocation(mainWorld);
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(spawnLocation);

        String coordMessage = String.format("§a%s が [x: %d, y: %d, z: %d] にスポーンしました！",
                player.getName(),
                spawnLocation.getBlockX(),
                spawnLocation.getBlockY(),
                spawnLocation.getBlockZ());

        Bukkit.broadcastMessage(coordMessage);
        player.sendMessage("§a観戦モードが解除され、ワールド '" + mainWorld.getName() + "' のランダムスポーンで復帰しました！");
    });
}

private Location findRandomSafeLocation(World world) {
    int attempts = 10;
    for (int i = 0; i < attempts; i++) {
        int x = random.nextInt(spawnRange * 2) - spawnRange;
        int z = random.nextInt(spawnRange * 2) - spawnRange;

        Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
        if (!chunk.isLoaded()) {
            chunk.load(true);
        }

        int y = world.getHighestBlockYAt(x, z);
        Material blockType = world.getBlockAt(x, y - 1, z).getType();

        if (blockType != Material.WATER && blockType != Material.LAVA && blockType != Material.POWDER_SNOW) {
            return new Location(world, x + 0.5, y + 1.0, z + 0.5);
        }
    }
    return world.getSpawnLocation();
}



    private World getMainWorld() {
        World world = Bukkit.getWorld(mainWorldName);
        return (world != null) ? world : Bukkit.getWorlds().get(0);
    }

    //====================================================
    // /checklives, /addlives, /checkrevive コマンド
    //====================================================
   @Override
public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player)) {
        sender.sendMessage("このコマンドはプレイヤーのみ実行可能です。");
        return true;
    }

    Player player = (Player) sender;
    UUID uuid = player.getUniqueId();
    long now = System.currentTimeMillis();

    if (command.getName().equalsIgnoreCase("checklives")) {
        int lives = remainingLives.getOrDefault(uuid, defaultDeathLimit);
        player.sendMessage("§aあなたの残りライフは " + lives + " です。");
        return true;
    }

    if (command.getName().equalsIgnoreCase("addlives")) {
        if (args.length == 0) {
            remainingLives.put(uuid, defaultDeathLimit);
            player.sendMessage("§aあなたのライフを初期値 (" + defaultDeathLimit + ") にリセットしました。");
            if (player.getGameMode() == GameMode.SPECTATOR) {
                releasePlayer(player);
                revivalTimestamps.remove(uuid);
            }
            saveData();
            return true;
        }

        if (args.length == 1) {
            String arg = args[0];
            try {
                int add = Integer.parseInt(arg);
                int current = remainingLives.getOrDefault(uuid, defaultDeathLimit);
                int newLives = current + add;
                remainingLives.put(uuid, newLives);
                player.sendMessage("§aあなたのライフを " + newLives + " に設定しました。");

                if (player.getGameMode() == GameMode.SPECTATOR && newLives > 0) {
                    releasePlayer(player);
                    revivalTimestamps.remove(uuid);
                }
                saveData();
                return true;
            } catch (NumberFormatException e) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(arg);
                if (target == null || target.getUniqueId() == null) {
                    player.sendMessage("§cプレイヤー " + arg + " は存在しません。");
                    return true;
                }
                UUID targetUuid = target.getUniqueId();
                remainingLives.put(targetUuid, defaultDeathLimit);
                player.sendMessage("§a" + target.getName() + " のライフを初期値 (" + defaultDeathLimit + ") にリセットしました。");

                Player onlineTarget = target.getPlayer();
                if (onlineTarget != null && onlineTarget.getGameMode() == GameMode.SPECTATOR) {
                    releasePlayer(onlineTarget);
                    revivalTimestamps.remove(targetUuid);
                }
                saveData();
                return true;
            }
        }

        if (args.length == 2) {
            String playerName = args[0];
            String amountStr = args[1];

            OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
            if (target == null || target.getUniqueId() == null) {
                player.sendMessage("§cプレイヤー " + playerName + " は存在しません。");
                return true;
            }
            UUID targetUuid = target.getUniqueId();

            try {
                int add = Integer.parseInt(amountStr);
                int current = remainingLives.getOrDefault(targetUuid, defaultDeathLimit);
                int newLives = current + add;
                remainingLives.put(targetUuid, newLives);

                player.sendMessage("§a" + target.getName() + " のライフを " + newLives + " に設定しました。");

                Player onlineTarget = target.getPlayer();
                if (onlineTarget != null && onlineTarget.getGameMode() == GameMode.SPECTATOR && newLives > 0) {
                    releasePlayer(onlineTarget);
                    revivalTimestamps.remove(targetUuid);
                }
                saveData();
            } catch (NumberFormatException e) {
                player.sendMessage("§c数値を正しく入力してください。");
            }
            return true;
        }

        player.sendMessage("§c用法: /addlives <player> <数値> または /addlives <数値>");
        return true;
    }

    if (command.getName().equalsIgnoreCase("checkrevive")) {
        int lives = remainingLives.getOrDefault(uuid, defaultDeathLimit);
        long revivalTime = revivalTimestamps.getOrDefault(uuid, 0L);

        if (lives > 0) {
            player.sendMessage("§eあなたはライフが残っているため、復活待ち状態ではありません！");
            return true;
        }
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.sendMessage("§e現在あなたは観戦モードではありません。");
            return true;
        }
        if (revivalTime <= now) {
            player.sendMessage("§aあなたはすでに復活可能な時間を過ぎています。復帰処理を行います。");

            remainingLives.put(uuid, defaultDeathLimit);
            revivalTimestamps.remove(uuid);
            releasePlayer(player);
            saveData();
            return true;
        }

        long diffMillis = revivalTime - now;
        long diffSec = diffMillis / 1000;
        long sec = diffSec % 60;
        long min = (diffSec / 60) % 60;
        long hours = (diffSec / 3600) % 24;
        long days = diffSec / 86400;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("日 ");
        if (hours > 0) sb.append(hours).append("時間 ");
        if (min > 0) sb.append(min).append("分 ");
        if (sec > 0) sb.append(sec).append("秒 ");
        String result = sb.toString().trim();
        if (result.isEmpty()) result = "数秒以内";

        player.sendMessage("§aあなたが復活できるまで残り §e" + result + " §aです。");
        return true;
    }

    return false;
}

private void checkAndSetSpectatorIfNeeded(Player player) {
    UUID uuid = player.getUniqueId();
    int lives = remainingLives.getOrDefault(uuid, defaultDeathLimit);
    long now = System.currentTimeMillis();
    long revivalTime = revivalTimestamps.getOrDefault(uuid, 0L);

    // ライフが0以下なら観戦モードチェック
    if (lives <= 0) {
        // 復活可能な時間を過ぎていればリリース
        if (revivalTime != 0L && now >= revivalTime) {
            releasePlayer(player);
            revivalTimestamps.remove(uuid);  // 復活タイムスタンプの削除
        } else {
            // まだ復活時間に達していないなら改めて観戦モードへ
            if (player.getGameMode() != GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SPECTATOR);
                player.sendMessage("§cあなたはまだ観戦モードの時間が残っています。");
                player.sendMessage("§c復活までの時間は「/checkrevive」で確認できます！");
            }
        }
    }
    // ライフが残っている場合は特に何もしない
}




}
