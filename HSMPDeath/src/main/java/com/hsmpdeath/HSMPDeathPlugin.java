package com.hsmpdeath;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Al morir:
 *  1. Suena item.trident.thunder (pitch 0 y pitch 2).
 *  2. El jugador pasa a modo espectador en el mismo lugar.
 *  3. Tras ~5 segundos es expulsado viendo la causa de muerte y el
 *     contador de su tiempo de revive (por defecto 3 horas).
 *  4. Cuando el tiempo expira y vuelve a entrar, se le teletransporta
 *     a su spawnpoint.
 *
 * /deathtime <n>s|t|m|h|d  -> ajusta el tiempo (segundos, ticks,
 * minutos, horas o dias) para las proximas muertes.
 */
public final class HSMPDeathPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final String DEATH_SOUND = "minecraft:item.trident.thunder";
    private static final long KICK_DELAY_TICKS = 100L; // ~5 segundos
    private static final long CALC_PERIOD_TICKS = 20L;
    private static final long DEFAULT_DEATH_TIME_MS = 3L * 60 * 60 * 1000; // 3h

    private record DeathRecord(String name, String message, long expiresAt,
                               Location deathLoc, Location respawnLoc) {
    }

    private final Map<UUID, DeathRecord> deaths = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> calcTasks = new ConcurrentHashMap<>();
    private File deathsFile;
    private long deathTimeMs = DEFAULT_DEATH_TIME_MS;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        deathTimeMs = getConfig().getLong("death-time-ms", DEFAULT_DEATH_TIME_MS);
        deathsFile = new File(getDataFolder(), "deaths.yml");
        loadDeaths();
        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("deathtime");
        if (cmd != null) {
            cmd.setExecutor(this);
        }
        var reviveCmd = getCommand("revive");
        if (reviveCmd != null) {
            reviveCmd.setExecutor(this);
        }
        getLogger().info("HSMPDeath habilitado. Tiempo de muerte: " + formatDuration(deathTimeMs));
    }

    @Override
    public void onDisable() {
        saveDeaths();
    }

    // ------------------------------------------------------------------
    // Muerte
    // ------------------------------------------------------------------
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Component deathMsg = event.deathMessage();
        String cause = deathMsg != null
                ? PlainTextComponentSerializer.plainText().serialize(deathMsg)
                : "Causa desconocida";

        Location deathLoc = player.getLocation().clone();
        Location respawn = player.getRespawnLocation();
        if (respawn == null || respawn.getWorld() == null) {
            respawn = Bukkit.getWorlds().get(0).getSpawnLocation();
        }

        long expiresAt = System.currentTimeMillis() + deathTimeMs;
        deaths.put(uuid, new DeathRecord(player.getName(), cause, expiresAt, deathLoc, respawn));
        saveDeaths();

        // Espectador en el mismo lugar, sin cambiar rotacion
        player.setGameMode(GameMode.SPECTATOR);

        // Sonido item.trident.thunder para TODO el servidor: pitch 0 y pitch 2
        broadcastSound(0.0f);
        Bukkit.getScheduler().runTaskLater(this, () -> broadcastSound(2.0f), 10L);

        // Actionbar con el log de la muerte en rojo bold durante los ~5s
        BukkitTask calc = Bukkit.getScheduler().runTaskTimer(this, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                long leftSec = Math.max(0,
                        (expiresAt - System.currentTimeMillis() + 999) / 1000);
                p.sendActionBar(deathActionBar(cause, leftSec));
            }
        }, CALC_PERIOD_TICKS, CALC_PERIOD_TICKS);
        calcTasks.put(uuid, calc);

        // A los ~5s: kick con causa de muerte + contador de revive
        Bukkit.getScheduler().runTaskLater(this, () -> {
            BukkitTask t = calcTasks.remove(uuid);
            if (t != null) {
                t.cancel();
            }
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                return;
            }
            p.kick(kickMessage(cause, expiresAt));
        }, KICK_DELAY_TICKS);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        DeathRecord rec = deaths.get(event.getPlayer().getUniqueId());
        if (rec == null || System.currentTimeMillis() >= rec.expiresAt()) {
            return;
        }
        // Mantenerlo en el lugar de la muerte como espectador
        event.setRespawnLocation(rec.deathLoc());
        event.getPlayer().setGameMode(GameMode.SPECTATOR);
    }

    // ------------------------------------------------------------------
    // Reingreso: revive teleportando al spawnpoint o sigue muerto
    // ------------------------------------------------------------------
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        DeathRecord rec = deaths.get(uuid);
        if (rec == null) {
            return;
        }
        if (System.currentTimeMillis() >= rec.expiresAt()) {
            deaths.remove(uuid);
            saveDeaths();
            Location loc = rec.respawnLoc();
            if (loc == null || loc.getWorld() == null) {
                loc = Bukkit.getWorlds().get(0).getSpawnLocation();
            }
            Location finalLoc = loc;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    p.teleport(finalLoc);
                    p.setGameMode(GameMode.SURVIVAL);
                    p.sendMessage(Component.text(
                            "Has revivido. Teletransportado a tu spawnpoint.",
                            NamedTextColor.GREEN));
                }
            }, 20L);
        } else {
            player.kick(kickMessage(rec.message(), rec.expiresAt()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BukkitTask t = calcTasks.remove(event.getPlayer().getUniqueId());
        if (t != null) {
            t.cancel();
        }
    }

    // ------------------------------------------------------------------
    // /deathtime
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("revive")) {
            return handleRevive(sender, args);
        }
        if (args.length == 0) {
            sender.sendMessage("Tiempo de muerte actual: " + formatDuration(deathTimeMs)
                    + " (aplica a nuevas muertes)");
            return true;
        }
        if (!sender.hasPermission("hsmpdeath.deathtime")) {
            sender.sendMessage("Sin permiso.");
            return true;
        }
        Matcher m = Pattern.compile("^(\\d+)\\s*([smthd]?)$", Pattern.CASE_INSENSITIVE)
                .matcher(args[0].trim());
        if (!m.matches()) {
            sender.sendMessage("Uso: /deathtime <cantidad><unidad>  "
                    + "unidades: s=segundos, t=ticks, m=minutos, h=horas, d=dias");
            sender.sendMessage("Ejemplos: /deathtime 30s, /deathtime 100t, "
                    + "/deathtime 10m, /deathtime 2h, /deathtime 1d");
            return true;
        }
        long amount = Long.parseLong(m.group(1));
        String unit = m.group(2).isEmpty() ? "s" : m.group(2).toLowerCase();
        long factor = switch (unit) {
            case "t" -> 50L;          // 1 tick = 50ms
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            default -> 1_000L;        // s
        };
        long newTime = amount * factor;
        if (newTime <= 0) {
            sender.sendMessage("El tiempo debe ser mayor que cero.");
            return true;
        }
        deathTimeMs = newTime;
        getConfig().set("death-time-ms", deathTimeMs);
        saveConfig();
        sender.sendMessage("Tiempo de muerte ajustado a " + formatDuration(deathTimeMs)
                + " para las proximas muertes.");
        return true;
    }

    private boolean handleRevive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hsmpdeath.revive")) {
            sender.sendMessage("Sin permiso.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage("Uso: /revive <jugador>");
            return true;
        }
        String target = args[0];
        // Buscar por UUID directo o por nombre
        DeathRecord rec = null;
        UUID targetUuid = null;
        for (Map.Entry<UUID, DeathRecord> e : deaths.entrySet()) {
            if (e.getValue().name().equalsIgnoreCase(target)) {
                rec = e.getValue();
                targetUuid = e.getKey();
                break;
            }
        }
        if (rec == null || targetUuid == null) {
            sender.sendMessage("El jugador '" + target + "' no esta muerto.");
            return true;
        }
        Player online = Bukkit.getPlayer(targetUuid);
        if (online != null && online.isOnline()) {
            deaths.remove(targetUuid);
            saveDeaths();
            Location loc = rec.respawnLoc();
            if (loc == null || loc.getWorld() == null) {
                loc = Bukkit.getWorlds().get(0).getSpawnLocation();
            }
            Location finalLoc = loc;
            Bukkit.getScheduler().runTask(this, () -> {
                online.teleport(finalLoc);
                online.setGameMode(GameMode.SURVIVAL);
                online.sendMessage(Component.text(
                        "Has sido revivido por un administrador.", NamedTextColor.GREEN));
            });
        } else {
            // Jugador offline: no se puede cambiar su gamemode ahora.
            // Se deja el registro marcado como expirado para que al entrar
            // pase por el flujo normal de revive (spawn + supervivencia).
            deaths.put(targetUuid, new DeathRecord(rec.name(), rec.message(), 0L,
                    rec.deathLoc(), rec.respawnLoc()));
            saveDeaths();
        }
        sender.sendMessage(target + " ya puede revivir.");
        getLogger().info(sender.getName() + " revivio a " + target);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label,
                                      String[] args) {
        if (command.getName().equalsIgnoreCase("revive") && args.length == 1) {
            String prefix = args[0].toLowerCase();
            return deaths.values().stream()
                    .map(DeathRecord::name)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .toList();
        }
        if (!command.getName().equalsIgnoreCase("deathtime") || args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase();
        return List.of("30s", "1m", "5m", "30m", "1h", "3h", "12h", "1d", "100t", "200t")
                .stream()
                .filter(s -> s.startsWith(prefix))
                .toList();
    }

    // ------------------------------------------------------------------
    // Persistencia de muertes (deaths.yml)
    // ------------------------------------------------------------------
    private void loadDeaths() {
        if (!deathsFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(deathsFile);
        ConfigurationSection section = yaml.getConfigurationSection("deaths");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                Location deathLoc = readLoc(s.getConfigurationSection("death-loc"));
                Location respawnLoc = readLoc(s.getConfigurationSection("respawn-loc"));
                deaths.put(uuid, new DeathRecord(
                        s.getString("name", "?"),
                        s.getString("message", "Causa desconocida"),
                        s.getLong("expires-at", 0),
                        deathLoc, respawnLoc));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveDeaths() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, DeathRecord> e : deaths.entrySet()) {
                String base = "deaths." + e.getKey();
                DeathRecord r = e.getValue();
                yaml.set(base + ".name", r.name());
                yaml.set(base + ".message", r.message());
                yaml.set(base + ".expires-at", r.expiresAt());
                writeLoc(yaml, base + ".death-loc", r.deathLoc());
                writeLoc(yaml, base + ".respawn-loc", r.respawnLoc());
            }
            yaml.save(deathsFile);
        } catch (IOException ignored) {
        }
    }

    private static Location readLoc(ConfigurationSection s) {
        if (s == null) {
            return null;
        }
        World w = Bukkit.getWorld(s.getString("world", "world"));
        if (w == null) {
            w = Bukkit.getWorlds().get(0);
        }
        return new Location(w,
                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    private static void writeLoc(YamlConfiguration yaml, String path, Location loc) {
        if (loc == null) {
            return;
        }
        yaml.set(path + ".world", loc.getWorld() != null ? loc.getWorld().getName() : "world");
        yaml.set(path + ".x", loc.getX());
        yaml.set(path + ".y", loc.getY());
        yaml.set(path + ".z", loc.getZ());
        yaml.set(path + ".yaw", (double) loc.getYaw());
        yaml.set(path + ".pitch", (double) loc.getPitch());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------
    private static Component kickMessage(String cause, long expiresAt) {
        String time = formatRemaining(expiresAt);
        return Component.text()
                .append(Component.text("========================================\n", NamedTextColor.DARK_RED))
                .append(Component.text("        ✠ HAS SIDO ELIMINADO ✠\n", NamedTextColor.RED))
                .append(Component.text("========================================\n", NamedTextColor.DARK_RED))
                .append(Component.text("\n"))
                .append(Component.text("Causa: ", NamedTextColor.GRAY))
                .append(Component.text(cause + "\n", NamedTextColor.WHITE))
                .append(Component.text("----------------------------------------\n", NamedTextColor.DARK_GRAY))
                .append(Component.text("\n"))
                .append(Component.text("Revive en: ", NamedTextColor.GRAY))
                .append(Component.text(time + "\n", NamedTextColor.YELLOW))
                .append(Component.text("----------------------------------------\n", NamedTextColor.DARK_GRAY))
                .append(Component.text("\n"))
                .append(Component.text("En espera de revive. Al llegar a cero\n", NamedTextColor.GRAY))
                .append(Component.text("vuelve y se te enviara a tu spawnpoint.\n", NamedTextColor.GRAY))
                .append(Component.text("\n"))
                .append(Component.text("========================================", NamedTextColor.DARK_RED))
                .build();
    }

    /** Reproduce el sonido de muerte a todos los jugadores online. */
    private void broadcastSound(float pitch) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), DEATH_SOUND, SoundCategory.MASTER, 1.0f, pitch);
        }
    }

    /** Actionbar: log de muerte en rojo bold (paleta roja). */
    private static Component deathActionBar(String cause, long leftSec) {
        return Component.text()
                .append(Component.text("☠ ", NamedTextColor.DARK_RED))
                .append(Component.text("MUERTE: ", NamedTextColor.RED))
                .append(Component.text(cause, NamedTextColor.RED))
                .append(Component.text("  ▸  revive en ", NamedTextColor.DARK_RED))
                .append(Component.text(leftSec + "s", NamedTextColor.RED))
                .decoration(TextDecoration.BOLD, true)
                .build();
    }

    private static String formatRemaining(long expiresAt) {
        return formatDuration(Math.max(0, expiresAt - System.currentTimeMillis()));
    }

    private static String formatDuration(long millis) {
        long totalSec = Math.max(0, millis / 1000);
        long d = totalSec / 86400;
        long h = (totalSec % 86400) / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append("d ");
        }
        if (h > 0 || d > 0) {
            sb.append(h).append("h ");
        }
        if (m > 0 || h > 0 || d > 0) {
            sb.append(m).append("m ");
        }
        sb.append(s).append('s');
        return sb.toString().trim();
    }
}
