package dev.endres;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Общее хранилище ресурсов на спавне.
 * Игроки кликают по блоку-хранилищу предметом, плагин забирает его и считает прогресс.
 * У блока нет инвентаря, поэтому достать предметы обратно невозможно.
 * Этапы и реплики Игниса здесь не участвуют: ими управляет MythicMobs через команды /endres.
 */
public final class EndResources extends JavaPlugin implements Listener, TabExecutor {

    private enum Phase { CLOSED, OPEN, COMPLETE }

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<Material, Integer> required = new LinkedHashMap<>();
    private final Map<Material, Integer> collected = new EnumMap<>(Material.class);

    private Phase phase = Phase.CLOSED;
    private boolean hasStorage;
    private String storageWorld;
    private int sx, sy, sz;

    private BossBar bar;
    private File dataFile;

    // ------------------------------------------------------------------ жизненный цикл

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        loadAll();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("endres") != null) {
            getCommand("endres").setExecutor(this);
            getCommand("endres").setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        saveData();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideBossBar(bar);
        }
    }

    private void loadAll() {
        reloadConfig();

        required.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("resources");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                int n = sec.getInt(key);
                if (m == null || !m.isItem()) {
                    getLogger().warning("Неизвестный предмет в resources: " + key);
                    continue;
                }
                if (n > 0) {
                    required.put(m, n);
                }
            }
        }

        collected.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        try {
            phase = Phase.valueOf(y.getString("phase", "CLOSED"));
        } catch (IllegalArgumentException ex) {
            phase = Phase.CLOSED;
        }
        hasStorage = y.isSet("storage.world");
        if (hasStorage) {
            storageWorld = y.getString("storage.world");
            sx = y.getInt("storage.x");
            sy = y.getInt("storage.y");
            sz = y.getInt("storage.z");
        }
        ConfigurationSection cs = y.getConfigurationSection("collected");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m != null) {
                    collected.put(m, cs.getInt(key));
                }
            }
        }
        refreshBar();
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("phase", phase.name());
        if (hasStorage) {
            y.set("storage.world", storageWorld);
            y.set("storage.x", sx);
            y.set("storage.y", sy);
            y.set("storage.z", sz);
        }
        for (Map.Entry<Material, Integer> en : collected.entrySet()) {
            y.set("collected." + en.getKey().name(), en.getValue());
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Не удалось сохранить data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ подсчёт и тексты

    private Component msg(String key, TagResolver... resolvers) {
        String raw = getConfig().getString("messages." + key, "<red>Нет текста: " + key + "</red>");
        return mm.deserialize(raw, resolvers);
    }

    private String itemName(Material m) {
        return getConfig().getString("names." + m.name(), m.name());
    }

    private int doneOf(Material m) {
        return Math.min(collected.getOrDefault(m, 0), required.getOrDefault(m, 0));
    }

    private int remaining(Material m) {
        return Math.max(0, required.getOrDefault(m, 0) - collected.getOrDefault(m, 0));
    }

    private int totalRequired() {
        int s = 0;
        for (int v : required.values()) {
            s += v;
        }
        return s;
    }

    private int totalDone() {
        int s = 0;
        for (Material m : required.keySet()) {
            s += doneOf(m);
        }
        return s;
    }

    private boolean allCollected() {
        return !required.isEmpty() && totalDone() >= totalRequired();
    }

    private void refreshBar() {
        int total = totalRequired();
        int done = totalDone();
        float progress = total == 0 ? 0f : Math.min(1f, (float) done / total);
        int percent = total == 0 ? 0 : (int) (done * 100L / total);
        bar.progress(progress);
        bar.name(msg("bossbar",
                Placeholder.unparsed("done", String.valueOf(done)),
                Placeholder.unparsed("total", String.valueOf(total)),
                Placeholder.unparsed("percent", String.valueOf(percent))));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (phase == Phase.OPEN) {
                p.showBossBar(bar);
            } else {
                p.hideBossBar(bar);
            }
        }
    }

    private Component statusComponent() {
        Component c = msg("status-header");
        for (Material m : required.keySet()) {
            c = c.appendNewline().append(msg("status-line",
                    Placeholder.unparsed("item", itemName(m)),
                    Placeholder.unparsed("done", String.valueOf(doneOf(m))),
                    Placeholder.unparsed("need", String.valueOf(required.get(m)))));
        }
        return c;
    }

    // ------------------------------------------------------------------ сдача предметов

    private boolean isStorage(Block b) {
        return hasStorage
                && b.getX() == sx && b.getY() == sy && b.getZ() == sz
                && b.getWorld().getName().equals(storageWorld);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null || !isStorage(b)) {
            return;
        }
        e.setCancelled(true); // блок не открывается, предмет не ставится
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player p = e.getPlayer();
        if (phase == Phase.CLOSED) {
            p.sendMessage(msg("closed"));
            return;
        }
        if (phase == Phase.COMPLETE) {
            p.sendMessage(msg("already-complete"));
            return;
        }

        Map<Material, Integer> given = new LinkedHashMap<>();
        if (p.isSneaking()) {
            // Shift + ПКМ: сдать всё нужное из инвентаря
            for (Material m : required.keySet()) {
                int t = take(p, m, remaining(m));
                if (t > 0) {
                    given.put(m, t);
                }
            }
        } else {
            // ПКМ: сдать стак из руки
            ItemStack hand = p.getInventory().getItemInMainHand();
            Material m = hand.getType();
            if (required.containsKey(m)) {
                int amount = Math.min(hand.getAmount(), remaining(m));
                if (amount > 0) {
                    if (amount >= hand.getAmount()) {
                        p.getInventory().setItemInMainHand(null);
                    } else {
                        ItemStack rest = hand.clone();
                        rest.setAmount(hand.getAmount() - amount);
                        p.getInventory().setItemInMainHand(rest);
                    }
                    given.put(m, amount);
                }
            }
        }

        if (given.isEmpty()) {
            p.sendMessage(statusComponent());
            return;
        }

        for (Map.Entry<Material, Integer> en : given.entrySet()) {
            collected.merge(en.getKey(), en.getValue(), Integer::sum);
        }
        saveData();
        refreshBar();
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);

        for (Map.Entry<Material, Integer> en : given.entrySet()) {
            Material m = en.getKey();
            Bukkit.broadcast(msg("deposit",
                    Placeholder.unparsed("player", p.getName()),
                    Placeholder.unparsed("item", itemName(m)),
                    Placeholder.unparsed("amount", String.valueOf(en.getValue())),
                    Placeholder.unparsed("done", String.valueOf(doneOf(m))),
                    Placeholder.unparsed("total", String.valueOf(required.get(m)))));
        }

        if (allCollected()) {
            complete();
        }
    }

    /** Забирает из инвентаря до max предметов типа m, возвращает сколько забрано. */
    private int take(Player p, Material m, int max) {
        if (max <= 0) {
            return 0;
        }
        ItemStack[] contents = p.getInventory().getStorageContents();
        int taken = 0;
        for (int i = 0; i < contents.length && taken < max; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != m) {
                continue;
            }
            int t = Math.min(it.getAmount(), max - taken);
            taken += t;
            if (t >= it.getAmount()) {
                contents[i] = null;
            } else {
                it.setAmount(it.getAmount() - t);
            }
        }
        p.getInventory().setStorageContents(contents);
        return taken;
    }

    private void complete() {
        phase = Phase.COMPLETE;
        saveData();
        refreshBar();
        Bukkit.broadcast(msg("complete"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        for (String cmd : getConfig().getStringList("on-complete")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (phase == Phase.OPEN) {
            e.getPlayer().showBossBar(bar);
        }
    }

    // ------------------------------------------------------------------ защита блока

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!isStorage(e.getBlock())) {
            return;
        }
        Player p = e.getPlayer();
        boolean admin = p.hasPermission("endres.admin") && p.getGameMode() == GameMode.CREATIVE;
        if (!admin) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isStorage);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isStorage);
    }

    @EventHandler
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (isStorage(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (isStorage(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (isStorage(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ команды

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("endres.admin")) {
            s.sendMessage(msg("no-permission"));
            return true;
        }
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                if (!hasStorage) {
                    s.sendMessage(msg("no-storage"));
                    return true;
                }
                if (required.isEmpty()) {
                    s.sendMessage(msg("no-resources"));
                    return true;
                }
                phase = Phase.OPEN;
                saveData();
                refreshBar();
                s.sendMessage(msg("started"));
                if (allCollected()) {
                    complete();
                }
            }
            case "stop" -> {
                phase = Phase.CLOSED;
                saveData();
                refreshBar();
                s.sendMessage(msg("stopped"));
            }
            case "reset" -> {
                collected.clear();
                phase = Phase.CLOSED;
                saveData();
                refreshBar();
                s.sendMessage(msg("reset-done"));
            }
            case "setstorage" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(msg("only-player"));
                    return true;
                }
                Block target = p.getTargetBlockExact(6);
                if (target == null || target.getType().isAir()) {
                    s.sendMessage(msg("look-at-block"));
                    return true;
                }
                hasStorage = true;
                storageWorld = target.getWorld().getName();
                sx = target.getX();
                sy = target.getY();
                sz = target.getZ();
                saveData();
                s.sendMessage(msg("storage-set",
                        Placeholder.unparsed("x", String.valueOf(sx)),
                        Placeholder.unparsed("y", String.valueOf(sy)),
                        Placeholder.unparsed("z", String.valueOf(sz))));
            }
            case "reload" -> {
                loadAll();
                s.sendMessage(msg("reloaded"));
            }
            default -> {
                s.sendMessage(msg("phase-line", Placeholder.unparsed("phase", phase.name())));
                s.sendMessage(statusComponent());
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : List.of("start", "stop", "reset", "setstorage", "status", "reload")) {
                if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) {
                    out.add(o);
                }
            }
        }
        return out;
    }
}
