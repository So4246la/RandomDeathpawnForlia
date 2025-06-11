package com.example.randomdeathpawn;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    // コンフィグから読み取るデフォルトライフ・復活時間・スポーン範囲
    private int defaultDeathLimit;
    private long defaultRevivalTimeHours;
    private int spawnRange;

    // サーバーのメインワールド名
    private final String mainWorldName = "world";

    // プレイヤーごとの残りライフ
    private final Map<UUID, Integer> remainingLives = new ConcurrentHashMap<>();
    // 観戦モードからの復活予定時刻 (ミリ秒)
    private final Map<UUID, Long> revivalTimestamps = new ConcurrentHashMap<>();

    // 次回リセット時刻 (ミリ秒)
    private long nextResetTime;
    // 既にアナウンスした「残り時間」を記録して重複通知を防ぐ
    private final Set<Long> announcedTimes = ConcurrentHashMap.newKeySet();

    // ランダムやタイマーなどで使用
    private final Random random = new Random();

    // Folia 対応スケジューラ
    private GlobalRegionScheduler globalScheduler;
    private AsyncScheduler asyncScheduler;

    // ★追加★ livedata.yml 用
    private File liveDataFile;
    private YamlConfiguration liveDataYaml;

    private enum TeleportReason {
        FIRST_JOIN,
        RESPAWN,
        SPECTATOR_RELEASE
    }    

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        defaultDeathLimit = config.getInt("deathLimit", 3);
        defaultRevivalTimeHours = config.getLong("revivalTimeHours", 1);
        spawnRange = config.getInt("spawnRange", 10000);

        this.globalScheduler = getServer().getGlobalRegionScheduler();
        this.asyncScheduler = getServer().getAsyncScheduler();

        loadData();

        getServer().getPluginManager().registerEvents(this, this);

        startRevivalCheckTask();
        scheduleWeeklyReset();
        startWeeklyResetAnnouncementTask();

        getLogger().info("RandomDeathpawn Ver1.3(Folia Compatible) が有効になりました！");
    }

    @Override
    public void onDisable() {
        // ★追加★ livedata.yml にデータを保存
        saveData();

        getLogger().info("RandomDeathpawn Ver1.3(Folia Compatible) が無効になりました！");
    }

    // ==================================================
    // livedata.yml の読み書き処理
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
        liveDataYaml.set("lives", null);
        for (Map.Entry<UUID, Integer> entry : remainingLives.entrySet()) {
            liveDataYaml.set("lives." + entry.getKey().toString(), entry.getValue());
        }        

        liveDataYaml.set("revivalTimestamps", null);
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
    // イベントハンドラ ＆ 固有ロジック
    // ==================================================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!player.hasPlayedBefore()) {
            // 新規ユーザー処理
            processRandomTeleport(player, TeleportReason.FIRST_JOIN);
            remainingLives.put(uuid, defaultDeathLimit);
            saveData();
        }

        if (player.isDead()) {
            // 死亡ユーザー処理
            getLogger().info(player.getName() + " が死亡状態でログインしたためリスポーン処理を実行します");
            // ログイン処理が完了するのを待つため、リスポーンとテレポートの処理全体を1ティック遅延させる
            player.getScheduler().runDelayed(this, (task) -> {
                player.spigot().respawn();
                processRandomTeleport(player, TeleportReason.RESPAWN);
            }, null, 1L);
        }        

        remainingLives.putIfAbsent(uuid, defaultDeathLimit);
        // プレイヤーに紐づくスケジューラを使用
        player.getScheduler().runDelayed(this, (task) -> checkAndSetSpectatorIfNeeded(player), null, 20L);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        getLogger().warning("### onPlayerDeath event triggered for " + event.getPlayer().getName() + " ###");

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        int lives = remainingLives.getOrDefault(uuid, defaultDeathLimit);
        lives--;
        if (lives < 0) {
            lives = 0;
        }
        remainingLives.put(uuid, lives);
        saveData();

        Component defaultDeathMessage = event.deathMessage();
        if (defaultDeathMessage == null) {
            defaultDeathMessage = Component.text(player.getName() + " died");
        }
        
        int displayLives = Math.max(lives, 0);
        Component addition = LegacyComponentSerializer.legacySection().deserialize(" §7[残りライフ: " + displayLives + "]");
        Component newDeathMessage = defaultDeathMessage.append(addition);
        event.deathMessage(newDeathMessage);

        if (lives <= 0) {
            // プレイヤーに紐づくスケジューラで1tick遅らせて実行
            player.getScheduler().run(this, (task) -> {
                player.setGameMode(GameMode.SPECTATOR);
                player.sendMessage("§cライフが0になりました。一定時間観戦モードになります。");
                player.sendMessage("§c復活までの時間は「/checkrevive」でいつでも確認できます！");
                long revivalDelayMillis = TimeUnit.HOURS.toMillis(defaultRevivalTimeHours);
                revivalTimestamps.put(uuid, System.currentTimeMillis() + revivalDelayMillis);
                saveData();
            }, null);            
        }

        // リスポーン処理
        player.spigot().respawn();
        player.getScheduler().runDelayed(this, (task) -> processRandomTeleport(player, TeleportReason.RESPAWN), null, 1L);
    }
        
    private void scheduleWeeklyReset() {
        long oneWeekMillis = TimeUnit.DAYS.toMillis(7);
        long oneWeekTicks = oneWeekMillis / 50;

        long now = System.currentTimeMillis();
        long delayMillis = Math.max(0, nextResetTime - now);
        long delayTicks = delayMillis / 50;

        globalScheduler.runAtFixedRate(this, (task) -> {
            remainingLives.keySet().forEach(uuid -> remainingLives.put(uuid, defaultDeathLimit));
            revivalTimestamps.clear();

            getLogger().info("一週間が経過したため、全員のライフを初期化しました。");
            this.broadcastMessage("§a一週間が経過したため、全員のライフを初期化しました。");

            // オンラインの観戦者を解放
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR) {
                    releasePlayer(p);
                }
            }

            long resetNow = System.currentTimeMillis();
            nextResetTime = resetNow + oneWeekMillis;
            announcedTimes.clear();
            saveData();
        }, delayTicks, oneWeekTicks);
    }

    private void startWeeklyResetAnnouncementTask() {
        globalScheduler.runAtFixedRate(this, (task) -> {
            long remainingMillis = nextResetTime - System.currentTimeMillis();
            if (remainingMillis <= 0)
                return;

            long days = TimeUnit.MILLISECONDS.toDays(remainingMillis);
            long hours = TimeUnit.MILLISECONDS.toHours(remainingMillis) % 24;
            long minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMillis);

            if (minutes > 60) {
                long timeKey = days * 24 + hours; // 1時間ごとのキー
                if (!announcedTimes.contains(timeKey)) {
                    this.broadcastMessage(
                            String.format("§6ライフリセットまで残り §e%d日 %d時間 §6です！ /checklives で現在のライフを確認できます。", days, hours));
                    this.broadcastMessage("§7(ライフ0の方は「/checkrevive」で復活までの時間を確認できます！)");
                    announcedTimes.add(timeKey);
                }
            } else { // 60分未満
                if (minutes == 30 || minutes == 15 || minutes == 5 || minutes == 1) {
                    long timeKey = minutes; // 分単位のキー
                    if (!announcedTimes.contains(timeKey)) {
                        this.broadcastMessage("§6ライフリセットまで残り §e" + minutes + "分 §6です！");
                        this.broadcastMessage("§7(ライフ0の方は「/checkrevive」で復活までの時間を確認できます！)");
                        announcedTimes.add(timeKey);
                    }
                }
            }
        }, 20L * 60, 20L * 60);
    }

    private void startRevivalCheckTask() {
        globalScheduler.runAtFixedRate(this, (task) -> {
            long now = System.currentTimeMillis();
            if (revivalTimestamps.isEmpty())
                return;

            revivalTimestamps.forEach((uuid, revivalTime) -> {
                if (revivalTime != null && revivalTime <= now) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.getGameMode() == GameMode.SPECTATOR) {
                        remainingLives.put(uuid, defaultDeathLimit);
                        releasePlayer(p);
                    }
                    // 処理が完了したらマップから削除
                    revivalTimestamps.remove(uuid);
                }
            });
        }, 20L, 20L);
    }

    private void releasePlayer(Player player) {
        // ゲームモードの変更はテレポート後に実行
        player.getScheduler().runDelayed(this, (task) -> {
            processRandomTeleport(player, TeleportReason.SPECTATOR_RELEASE);
        }, null, 1L);
    }

    private void processRandomTeleport(Player player, TeleportReason reason) {
        World respawnWorld = this.getMainWorld();
        if (respawnWorld == null) {
            getLogger().severe("リスポーン先のワールドが見つかりません！");
            player.sendMessage("§cリスポーン先のワールドが見つからなかったため、テレポートできませんでした。");
            return;
        }

        findAndTeleportWithRetries(player, respawnWorld, reason, 10);
    }

    private void findAndTeleportWithRetries(Player player, World world, TeleportReason reason, int retriesLeft) {
        if (retriesLeft <= 0) {
            player.getScheduler().run(this, (task) -> {
                player.sendMessage("§c安全なテレポート先が見つかりませんでした。ワールドのスポーン地点に移動します。");
                player.teleportAsync(world.getSpawnLocation());
            }, null);
            return;
        }

        findSafeLocation(world).thenAccept(safeLocation -> {
            player.getScheduler().run(this, (task) -> {
                CompletableFuture<Boolean> teleportFuture = player.teleportAsync(safeLocation);

                // テレポート完了後の処理
                teleportFuture.thenAccept(success -> {
                    if (success) {
                        // テレポート成功時の処理 (ここは変更なし)
                        Location loc = player.getLocation();

                        String coordMessage;
                        switch (reason) {
                            case FIRST_JOIN:
                                coordMessage = String.format("§e初参加のプレイヤー %s が [x: %d, y: %d, z: %d] にスポーンしました！",
                                        player.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                                this.broadcastMessage(coordMessage);

                                // プレイヤーのスケジューラを使い、個人メッセージだけを遅延させる
                                player.getScheduler().runDelayed(this, task2 -> {
                                    player.sendMessage(
                                            String.format("§a初参加なのでランダムスポーン地点 [x: %d, y: %d, z: %d] へテレポートしました！",
                                                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
                                }, null, 40L);
                                break;
                            case RESPAWN:
                                coordMessage = String.format("§b%s が [x: %d, y: %d, z: %d] にスポーンしました！",
                                        player.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                                this.broadcastMessage(coordMessage);

                                // // プレイヤーのスケジューラを使い、個人メッセージだけを遅延させる
                                // player.getScheduler().runDelayed(this, task2 -> {
                                //     player.sendMessage(coordMessage);
                                // }, null, 40L);

                                break;
                            case SPECTATOR_RELEASE:
                                // ゲームモードをサバイバルに変更（テレポート後に変更しないと落下ダメージを受ける場合がある）
                                player.setGameMode(GameMode.SURVIVAL);

                                coordMessage = String.format("§b%s が [x: %d, y: %d, z: %d] にスポーンしました！",
                                        player.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                                this.broadcastMessage(coordMessage);
                                player.sendMessage("§a観戦モードが解除され、ランダムな地点で復帰しました！");
                                break;
                        }
                    }
                });

                player.sendMessage("§aテレポートが完了しました！");
            }, null);
        }).exceptionally(ex -> {
            getLogger().info(player.getName() + " のテレポート先探索に失敗、または不適切な場所でした。リトライします... (残り: " + (retriesLeft - 1) + "回)");
            findAndTeleportWithRetries(player, world, reason, retriesLeft - 1);
            return null;
        });
    }

    /**
     * ワールド内で安全なランダム座標を非同期で検索する
     */
    private CompletableFuture<Location> findSafeLocation(World world) {
        int x = random.nextInt(spawnRange * 2) - spawnRange;
        int z = random.nextInt(spawnRange * 2) - spawnRange;

        return world.getChunkAtAsync(x >> 4, z >> 4).thenApply(chunk -> {
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            
            // プレイヤーの足元となるブロックを取得
            Material blockType = world.getBlockAt(x, y, z).getType();

            // ブロックが水、溶岩、粉雪であれば、例外をスローしてこの場所を「失敗」扱いにする
            if (blockType == Material.WATER || blockType == Material.LAVA || blockType == Material.POWDER_SNOW) {
                throw new IllegalStateException("Unsafe spawn location found: " + blockType.name());
            }

            // 問題なければLocationオブジェクトを生成して返す
            return new Location(world, x + 0.5, y + 1.0, z + 0.5);
        });
    }

    private World getMainWorld() {
        World world = Bukkit.getWorld(mainWorldName);
        return (world != null) ? world : Bukkit.getWorlds().get(0);
    }

    // ====================================================
    // /checklives, /addlives, /checkrevive コマンド
    // ====================================================
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
            if (days > 0)
                sb.append(days).append("日 ");
            if (hours > 0)
                sb.append(hours).append("時間 ");
            if (min > 0)
                sb.append(min).append("分 ");
            if (sec > 0)
                sb.append(sec).append("秒 ");
            String result = sb.toString().trim();
            if (result.isEmpty())
                result = "数秒以内";

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
                revivalTimestamps.remove(uuid); // 復活タイムスタンプの削除
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

    /**
     * Bukkit.broadcastMessageが非推奨なので代替メッセージ
     */
    private void broadcastMessage(String message) {
        Component component = LegacyComponentSerializer.legacySection().deserialize(message);
        Bukkit.broadcast(component);
        //Bukkit.broadcastMessage(message);
    }
}
