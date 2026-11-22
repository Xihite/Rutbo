package com.difficult;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Cambio de dificultad diario:
 *  - Las herramientas de madera, piedra y cobre se desgastan mas rapido.
 *  - Los arcos pueden romperse; se craftea un Arco Reforzado poniendo un
 *    arco en el centro del crafteo rodeado de hilos y palos.
 *  - Los zombies tienen una probabilidad de portar una espada.
 *
 * Cada jugador que entre sin tener el libro recibe el "libro del cambio
 * de dificultad del dia" con el resumen de los cambios.
 */
public final class DifficultPlugin extends JavaPlugin implements Listener {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    private NamespacedKey bookKey;
    private NamespacedKey bowKey;
    private NamespacedKey recipeKey;
    private int wearMultiplier;
    private double zombieSwordChance;
    private Material zombieSword;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        wearMultiplier = Math.max(1, getConfig().getInt("wear-multiplier", 2));
        zombieSwordChance = getConfig().getDouble("zombie-sword-chance", 0.5);
        zombieSword = Material.matchMaterial(
                getConfig().getString("zombie-sword", "IRON_SWORD"));
        if (zombieSword == null || !zombieSword.isItem()) {
            zombieSword = Material.IRON_SWORD;
        }
        zombieSwordChance = Math.max(0.0, Math.min(1.0, zombieSwordChance));

        bookKey = new NamespacedKey(this, "daily_book");
        bowKey = new NamespacedKey(this, "reinforced_bow");
        recipeKey = new NamespacedKey(this, "reinforced_bow");

        registerReinforcedBowRecipe();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("Difficult habilitado. Desgaste x" + wearMultiplier
                + ", espada zombie " + Math.round(zombieSwordChance * 100) + "%");
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
        meta.title(Component.text("Cambio de dificultad — " + date,
                NamedTextColor.GOLD));
        meta.author(Component.text("Difficult", NamedTextColor.GRAY));
        meta.pages(
                List.of(
                        Component.text("Atención, aventurero.", NamedTextColor.YELLOW),
                        Component.text("A partir de hoy el servidor tiene una nueva"
                                + " dificultad. Esto es lo que cambia:", NamedTextColor.WHITE),
                        Component.text("• Las herramientas de madera, piedra y cobre"
                                + " se desgastan " + wearMultiplier + " veces más rápido.",
                                NamedTextColor.WHITE),
                        Component.text("• Los arcos pueden romperse con el uso."
                                + " Craftea un Arco Reforzado colocando un arco en el"
                                + " centro del crafteo, rodeado de hilos y palos.",
                                NamedTextColor.WHITE),
                        Component.text("• Los zombies tienen un "
                                + Math.round(zombieSwordChance * 100)
                                + "% de probabilidad de portar una espada.",
                                NamedTextColor.WHITE),
                        Component.text("¡Buena suerte!", NamedTextColor.YELLOW)
                                .decoration(TextDecoration.BOLD, true))
                );
        book.setItemMeta(meta);
        return book;
    }

    // ------------------------------------------------------------------
    // Desgaste acelerado de herramientas y arcos
    // ------------------------------------------------------------------
    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        Material type = item.getType();
        if (type == Material.BOW || type == Material.CROSSBOW) {
            if (isReinforced(item)) {
                return; // el arco reforzado conserva su durabilidad normal
            }
            event.setDamage(event.getDamage() * wearMultiplier);
            return;
        }
        if (isPenaltyTool(type)) {
            event.setDamage(event.getDamage() * wearMultiplier);
        }
    }

    private boolean isPenaltyTool(Material type) {
        String name = type.name();
        for (String tier : new String[]{"WOODEN_", "STONE_", "COPPER_"}) {
            if (!name.startsWith(tier)) {
                continue;
            }
            return name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                    || name.endsWith("_SHOVEL") || name.endsWith("_HOE");
        }
        return false;
    }

    private boolean isReinforced(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(bowKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------
    // Zombies con espada (probabilidad configurable)
    // ------------------------------------------------------------------
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= zombieSwordChance) {
            return;
        }
        if (zombie.getEquipment() == null
                || !zombie.getEquipment().getItemInMainHand().getType().isAir()) {
            return;
        }
        zombie.getEquipment().setItemInMainHand(new ItemStack(zombieSword));
    }
}
