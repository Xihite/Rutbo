package com.difficult;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Cambio de dificultad diario:
 *  - Los arcos sufren un 50% mas de desgaste al dispararse (el Arco
 *    Reforzado esta exento).
 *  - Las espadas de madera se rompen 10 veces mas rapido.
 *  - El 50% de los zombies portan una hacha de hierro y corren mas
 *    rapido (efecto Velocidad).
 *  - Los esqueletos disparan flechas que, al impactar, liberan balas de
 *    shulker contra la entidad viva mas cercana.
 *
 * Cada jugador que entre sin tener el libro recibe el "libro del cambio
 * de dificultad del dia" con el resumen de los cambios.
 */
public final class DifficultPlugin extends JavaPlugin implements Listener {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);
    private static final TextColor ORANGE = TextColor.color(0xCC5500);

    private NamespacedKey bookKey;
    private NamespacedKey bowKey;
    private NamespacedKey recipeKey;
    private double bowWearMultiplier;
    private int swordWearMultiplier;
    private double zombieEquipChance;
    private int zombieSpeedAmplifier;
    private int shulkerBulletCount;
    private double shulkerTargetRadius;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bowWearMultiplier = getConfig().getDouble("bow-wear-multiplier", 1.5);
        swordWearMultiplier = Math.max(1, getConfig().getInt("wooden-sword-wear-multiplier", 10));
        zombieEquipChance = clamp01(getConfig().getDouble("zombie-equip-chance", 0.5));
        zombieSpeedAmplifier = Math.max(0, getConfig().getInt("zombie-speed-amplifier", 0));
        shulkerBulletCount = Math.max(1, getConfig().getInt("shulker-bullet-count", 5));
        shulkerTargetRadius = Math.max(1.0, getConfig().getDouble("shulker-target-radius", 16.0));

        bookKey = new NamespacedKey(this, "daily_book");
        bowKey = new NamespacedKey(this, "reinforced_bow");
        recipeKey = new NamespacedKey(this, "reinforced_bow");

        registerReinforcedBowRecipe();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("Difficult habilitado. Arco x" + bowWearMultiplier
                + ", espada madera x" + swordWearMultiplier
                + ", zombie equip " + Math.round(zombieEquipChance * 100) + "%"
                + ", balas shulker " + shulkerBulletCount);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ------------------------------------------------------------------
    // Receta: Arco Reforzado (arco en el centro rodeado de hilos y palos)
    //   S T S
    //   S B S
    //   S T S
    // ------------------------------------------------------------------
    private void registerReinforcedBowRecipe() {
        ItemStack result = new ItemStack(Material.BOW);
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(bowKey, PersistentDataType.BYTE, (byte) 1);
            meta.lore(List.of(Component.text("» Reforzado «", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false)));
            result.setItemMeta(meta);
        }
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, result);
        recipe.shape("STS", "SBS", "STS");
        recipe.setIngredient('S', Material.STRING);
        recipe.setIngredient('T', Material.STICK);
        recipe.setIngredient('B', Material.BOW);
        Bukkit.addRecipe(recipe);
    }

    // ------------------------------------------------------------------
    // Libro del cambio de dificultad: se entrega al entrar si no lo tiene
    // ------------------------------------------------------------------
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (hasDailyBook(player)) {
            return;
        }
        player.getInventory().addItem(dailyBook());
        player.sendMessage(Component.text(
                "Recibiste el libro «Cambio de dificultad» en tu inventario.",
                NamedTextColor.GOLD));
    }

    private boolean hasDailyBook(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != Material.WRITTEN_BOOK) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.getPersistentDataContainer()
                    .has(bookKey, PersistentDataType.BYTE)) {
                return true;
            }
        }
        return false;
    }

    private ItemStack dailyBook() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) {
            return book;
        }
        String date = LocalDate.now().format(DATE_FMT);
        meta.getPersistentDataContainer().set(bookKey, PersistentDataType.BYTE, (byte) 1);
        meta.title(Component.text("Cambio de dificultad — " + date, ORANGE)
                .decoration(TextDecoration.BOLD, true));
        meta.author(Component.text("Difficult", NamedTextColor.DARK_GRAY));
        meta.pages(
                List.of(
                        page(
                                line("⚠ CAMBIO DE DIFICULTAD", ORANGE)
                                        .decoration(TextDecoration.BOLD, true),
                                line("A partir de hoy el servidor tiene una nueva"
                                        + " dificultad:", NamedTextColor.BLACK)),
                        page(
                                bullet("Los arcos sufren un 50% más de desgaste al"
                                        + " dispararse. El Arco Reforzado está exento."),
                                bullet("Las espadas de madera se rompen "
                                        + swordWearMultiplier + " veces más rápido."),
                                bullet("El 50% de los zombies portan una hacha de"
                                        + " hierro y corren más rápido."),
                                bullet("Los esqueletos disparan flechas que, al"
                                        + " impactar, liberan balas de shulker contra"
                                        + " la entidad más cercana.")),
                        page(
                                line("¡Buena suerte, aventurero!", ORANGE)
                                        .decoration(TextDecoration.BOLD, true),
                                line("— Difficult", ORANGE)))
                );
        book.setItemMeta(meta);
        return book;
    }

    /** Une varias lineas en una sola pagina de libro. */
    private static Component page(Component... lines) {
        Component out = Component.empty();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out = out.append(Component.newline());
            }
            out = out.append(lines[i]);
        }
        return out;
    }

    private static Component line(String text, TextColor color) {
        return Component.text(text, color);
    }

    private static Component bullet(String text) {
        return Component.text("• ", ORANGE)
                .append(Component.text(text, NamedTextColor.BLACK));
    }

    // ------------------------------------------------------------------
    // Desgaste: arcos +50% al disparar, espadas de madera x10
    // ------------------------------------------------------------------
    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        Material type = item.getType();
        if (type == Material.BOW || type == Material.CROSSBOW) {
            if (isReinforced(item)) {
                return; // el arco reforzado conserva su durabilidad normal
            }
            event.setDamage((int) Math.ceil(event.getDamage() * bowWearMultiplier));
            return;
        }
        if (type == Material.WOODEN_SWORD) {
            event.setDamage(event.getDamage() * swordWearMultiplier);
        }
    }

    private boolean isReinforced(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(bowKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------
    // Zombies: 50% llevan hacha de hierro y corren mas rapido
    // ------------------------------------------------------------------
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= zombieEquipChance) {
            return;
        }
        if (zombie.getEquipment() == null
                || !zombie.getEquipment().getItemInMainHand().getType().isAir()) {
            return;
        }
        zombie.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_AXE));
        zombie.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                PotionEffect.INFINITE_DURATION, zombieSpeedAmplifier,
                false, false, false));
    }

    // ------------------------------------------------------------------
    // Esqueletos: la flecha impacta -> balas de shulker a la entidad
    // viva mas cercana al punto de impacto
    // ------------------------------------------------------------------
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)
                || !(arrow.getShooter() instanceof AbstractSkeleton shooter)) {
            return;
        }
        World world = arrow.getWorld();
        Location loc = arrow.getLocation();
        Entity target = nearestLiving(world, loc, shooter);
        if (target == null) {
            return;
        }
        for (int i = 0; i < shulkerBulletCount; i++) {
            ShulkerBullet bullet = world.spawn(loc, ShulkerBullet.class);
            bullet.setTarget(target);
            bullet.setVelocity(new Vector(
                    (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.8,
                    0.35 + ThreadLocalRandom.current().nextDouble() * 0.35,
                    (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.8));
        }
    }

    private Entity nearestLiving(World world, Location loc, Entity exclude) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : world.getNearbyEntities(loc,
                shulkerTargetRadius, shulkerTargetRadius, shulkerTargetRadius)) {
            if (e == exclude || !(e instanceof LivingEntity le) || le.isDead()
                    || !e.isValid()) {
                continue;
            }
            if (e instanceof Player p && (p.getGameMode() == GameMode.CREATIVE
                    || p.getGameMode() == GameMode.SPECTATOR)) {
                continue;
            }
            double d = e.getLocation().distanceSquared(loc);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }
}
