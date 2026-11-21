package com.qauth;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.connection.PlayerCommonConnection;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class QAuthPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final long AUTH_TIMEOUT_SECONDS = 120;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int MIN_PASSWORD_LENGTH = 4;
    private static final int MAX_PASSWORD_LENGTH = 32;

    private static final Key KEY_REGISTER = Key.key("qauth", "register");
    private static final Key KEY_LOGIN = Key.key("qauth", "login");
    private static final Key KEY_CANCEL = Key.key("qauth", "cancel");
    private static final Key KEY_WELCOME = Key.key("qauth", "welcome_done");

    /** Protocolo mínimo para diálogos: Minecraft 1.21.6 = 771. */
    private static final int MIN_DIALOG_PROTOCOL = 771;

    private final AccountStore store = new AccountStore(
            new java.io.File(getDataFolder(), "accounts.yml"));
    private final Map<String, PendingAuth> pending = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, ChatSession> chatAuth = new ConcurrentHashMap<>();
    private boolean ipAutoLogin = true;

    /** Sesión de autenticación por chat (clientes <1.21.6). */
    private static final class ChatSession {
        final String name;
        int attempts;
        org.bukkit.scheduler.BukkitTask kickTask;

        ChatSession(String name) {
            this.name = name;
        }
    }

    private record PendingAuth(String name, String ip, boolean register,
                               int attempts, CompletableFuture<Boolean> future) {
        PendingAuth withAttempts(int a) {
            return new PendingAuth(name, ip, register, a, future);
        }
    }

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        saveDefaultConfig();
        ipAutoLogin = getConfig().getBoolean("ip-auto-login", true);
        store.load();
        Bukkit.getPluginManager().registerEvents(this, this);
        var loginCmd = getCommand("login");
        if (loginCmd != null) {
            loginCmd.setExecutor(this);
        }
        var registerCmd = getCommand("register");
        if (registerCmd != null) {
            registerCmd.setExecutor(this);
        }
        getLogger().info("qAuth habilitado. Cuentas cargadas: " + store.size());
    }

    @Override
    public void onDisable() {
        store.save();
    }

    // ------------------------------------------------------------------
    // Fase de configuración (pantalla de carga): bloquea el join hasta
    // que el jugador complete registro o login en el diálogo.
    // ------------------------------------------------------------------
    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection conn = event.getConnection();
        PlayerProfile profile = conn.getProfile();
        String name = profile.getName();
        if (name == null) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        String ip = normalizeIp(conn.getAddress());

        // Clientes antiguos (pre-1.21.6) no soportan diálogos: entran al
        // mundo y se autentican por chat (/login, /register). El bloqueo
        // se aplica en onJoin.
        int protocol = clientProtocol(conn.getProfile().getId());
        if (protocol != -1 && protocol < MIN_DIALOG_PROTOCOL) {
            return;
        }

        // Mismo nombre + misma IP que la última autenticación: acceso directo.
        var account = store.get(name);
        if (ipAutoLogin && account.isPresent() && account.get().ip().equals(ip)) {
            return;
        }

        PendingAuth pa = new PendingAuth(key, ip, account.isEmpty(), 0,
                new CompletableFuture<>());
        pa.future().completeOnTimeout(false, AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pending.put(key, pa);

        if (pa.register()) {
            conn.getAudience().showDialog(registerDialog(pa.name(), null));
        } else {
            conn.getAudience().showDialog(loginDialog(pa.name(), null));
        }

        boolean ok;
        try {
            ok = pa.future().join();
        } finally {
            pending.remove(key, pa);
        }

        if (!ok || !conn.isConnected()) {
            if (conn.isConnected()) {
                conn.disconnect(Component.text(
                        "qAuth: inicio de sesión cancelado o tiempo agotado.",
                        NamedTextColor.RED));
            }
        }
    }

    // ------------------------------------------------------------------
    // Respuestas de los botones del diálogo.
    // ------------------------------------------------------------------
    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection conn)) {
            return;
        }
        PlayerProfile profile = conn.getProfile();
        if (profile.getName() == null) {
            return;
        }
        String key = profile.getName().toLowerCase(Locale.ROOT);
        PendingAuth pa = pending.get(key);
        if (pa == null) {
            return;
        }

        Key id = event.getIdentifier();
        DialogResponseView view = event.getDialogResponseView();

        if (id.equals(KEY_CANCEL)) {
            pa.future().complete(false);
            return;
        }
        if (id.equals(KEY_WELCOME)) {
            pa.future().complete(true);
            return;
        }

        if (id.equals(KEY_REGISTER)) {
            handleRegister(conn, pa, view);
        } else if (id.equals(KEY_LOGIN)) {
            handleLogin(conn, pa, view);
        }
    }

    private void handleRegister(PlayerConfigurationConnection conn, PendingAuth pa,
                                DialogResponseView view) {
        String pass = view.getText("password");
        String confirm = view.getText("confirm");
        if (pass == null || confirm == null
                || pass.length() < MIN_PASSWORD_LENGTH
                || pass.length() > MAX_PASSWORD_LENGTH) {
            resend(conn, pa, registerDialog(pa.name(),
                    "La contraseña debe tener entre " + MIN_PASSWORD_LENGTH
                            + " y " + MAX_PASSWORD_LENGTH + " caracteres."));
            return;
        }
        if (!pass.equals(confirm)) {
            resend(conn, pa, registerDialog(pa.name(), "Las contraseñas no coinciden."));
            return;
        }
        store.register(pa.name(), pa.ip(), pass);
        getLogger().info("Cuenta registrada: " + pa.name() + " (IP " + pa.ip() + ")");
        if (getConfig().getBoolean("welcome.enabled", true)) {
            // Mostrar el mensaje de bienvenida en diálogo antes de entrar
            conn.getAudience().showDialog(welcomeDialog(false));
        } else {
            pa.future().complete(true);
        }
    }

    private void handleLogin(PlayerConfigurationConnection conn, PendingAuth pa,
                             DialogResponseView view) {
        String pass = view.getText("password");
        if (pass == null || pass.isEmpty()) {
            resend(conn, pa, loginDialog(pa.name(), "Introduce tu contraseña."));
            return;
        }
        if (store.verifyPassword(pa.name(), pass)) {
            store.touchLogin(pa.name(), pa.ip());
            getLogger().info("Login correcto: " + pa.name() + " (IP " + pa.ip() + ")");
            pa.future().complete(true);
            return;
        }
        int attempts = pa.attempts() + 1;
        if (attempts >= MAX_LOGIN_ATTEMPTS) {
            getLogger().warning("Demasiados intentos fallidos para " + pa.name());
            pa.future().complete(false);
            return;
        }
        pending.put(pa.name(), pa.withAttempts(attempts));
        resend(conn, pa.withAttempts(attempts), loginDialog(pa.name(),
                "Contraseña incorrecta. Intentos restantes: "
                        + (MAX_LOGIN_ATTEMPTS - attempts) + "."));
    }

    private void resend(PlayerConfigurationConnection conn, PendingAuth pa, Dialog dialog) {
        pending.put(pa.name(), pa);
        conn.getAudience().showDialog(dialog);
    }

    // ------------------------------------------------------------------
    // Limpieza si el jugador desconecta mientras espera.
    // ------------------------------------------------------------------
    @EventHandler
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        PendingAuth pa = pending.remove(event.getPlayerName().toLowerCase(Locale.ROOT));
        if (pa != null) {
            pa.future().complete(false);
        }
    }

    // ------------------------------------------------------------------
    // Fallback por chat para clientes <1.21.6 (sin soporte de diálogos)
    // ------------------------------------------------------------------
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Advertencia del servidor en cada ingreso (config.yml -> welcome)
        sendWelcome(player);
        int protocol = clientProtocol(player.getUniqueId());
        // Solo clientes antiguos: los modernos ya pasaron por el diálogo
        // en la fase de configuración.
        if (protocol == -1 || protocol >= MIN_DIALOG_PROTOCOL) {
            return;
        }

        String name = player.getName();
        String ip = normalizeIp(player.getAddress());
        var account = store.get(name);
        if (ipAutoLogin && account.isPresent() && account.get().ip().equals(ip)) {
            return; // IP reconocida: acceso directo, sin bloqueo
        }

        ChatSession session = new ChatSession(name.toLowerCase(Locale.ROOT));
        chatAuth.put(player.getUniqueId(), session);
        session.kickTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = Bukkit.getPlayer(player.getUniqueId());
            if (p != null && chatAuth.remove(player.getUniqueId(), session)) {
                p.kick(Component.text(
                        "qAuth: tiempo agotado para iniciar sesión.", NamedTextColor.RED));
            }
        }, AUTH_TIMEOUT_SECONDS * 20L);

        player.sendMessage(Component.text(
                "===============================================", NamedTextColor.GOLD));
        if (store.contains(name)) {
            player.sendMessage(Component.text(
                    " Tu IP cambió. Usa /login <contraseña> para entrar.", NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text(
                    " Bienvenido. Regístrate: /register <contraseña> <confirmar>",
                    NamedTextColor.YELLOW));
        }
        player.sendMessage(Component.text(
                " Tienes " + AUTH_TIMEOUT_SECONDS + " segundos.", NamedTextColor.GRAY));
        player.sendMessage(Component.text(
                "===============================================", NamedTextColor.GOLD));
    }

    private boolean isChatLocked(java.util.UUID uuid) {
        return chatAuth.containsKey(uuid);
    }

    @EventHandler
    public void onChatAuthMove(PlayerMoveEvent event) {
        if (!isChatLocked(event.getPlayer().getUniqueId())) {
            return;
        }
        var to = event.getTo();
        var from = event.getFrom();
        if (to != null && (from.getX() != to.getX()
                || from.getY() != to.getY() || from.getZ() != to.getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChatAuthTeleport(PlayerTeleportEvent event) {
        if (isChatLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChatAuthInteract(PlayerInteractEvent event) {
        if (isChatLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChatAuthCommand(PlayerCommandPreprocessEvent event) {
        if (!isChatLocked(event.getPlayer().getUniqueId())) {
            return;
        }
        String msg = event.getMessage().toLowerCase(Locale.ROOT);
        if (msg.equals("/login") || msg.startsWith("/login ")
                || msg.equals("/register") || msg.startsWith("/register ")) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text(
                "qAuth: debes autenticarte primero (/login o /register).",
                NamedTextColor.RED));
    }

    @EventHandler
    public void onChatAuthChat(AsyncPlayerChatEvent event) {
        if (isChatLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ChatSession s = chatAuth.remove(event.getPlayer().getUniqueId());
        if (s != null && s.kickTask != null) {
            s.kickTask.cancel();
        }
    }

    // ------------------------------------------------------------------
    // Comandos /login y /register (fallback chat)
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo jugadores.");
            return true;
        }
        java.util.UUID uuid = player.getUniqueId();
        ChatSession session = chatAuth.get(uuid);
        if (session == null) {
            player.sendMessage(Component.text(
                    "qAuth: no tienes una sesión de acceso pendiente.",
                    NamedTextColor.RED));
            return true;
        }
        String name = session.name;

        if (command.getName().equalsIgnoreCase("register")) {
            if (store.contains(name)) {
                player.sendMessage(Component.text(
                        "qAuth: ya tienes cuenta. Usa /login <contraseña>.",
                        NamedTextColor.RED));
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(Component.text(
                        "Uso: /register <contraseña> <confirmación>", NamedTextColor.YELLOW));
                return true;
            }
            String pass = args[0];
            if (pass.length() < MIN_PASSWORD_LENGTH || pass.length() > MAX_PASSWORD_LENGTH) {
                player.sendMessage(Component.text(
                        "La contraseña debe tener entre " + MIN_PASSWORD_LENGTH
                                + " y " + MAX_PASSWORD_LENGTH + " caracteres.",
                        NamedTextColor.RED));
                return true;
            }
            if (!pass.equals(args[1])) {
                player.sendMessage(Component.text(
                        "Las contraseñas no coinciden.", NamedTextColor.RED));
                return true;
            }
            String ip = normalizeIp(player.getAddress());
            store.register(name, ip, pass);
            unlock(player, session, "Cuenta creada. ¡Bienvenido, " + player.getName() + "!");
            getLogger().info("Cuenta registrada por chat: " + name + " (IP " + ip + ")");
            return true;
        }

        // /login
        if (args.length != 1) {
            player.sendMessage(Component.text("Uso: /login <contraseña>", NamedTextColor.YELLOW));
            return true;
        }
        String pass = args[0];
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            boolean ok = store.verifyPassword(name, pass);
            Bukkit.getScheduler().runTask(this, () -> {
                if (!chatAuth.containsKey(uuid)) {
                    return; // ya autenticado o desconectado
                }
                if (ok) {
                    String ip = normalizeIp(player.getAddress());
                    store.touchLogin(name, ip);
                    unlock(player, session,
                            "Login correcto. ¡Bienvenido de nuevo!");
                    getLogger().info("Login por chat: " + name + " (IP " + ip + ")");
                } else {
                    int attempts = ++session.attempts;
                    if (attempts >= MAX_LOGIN_ATTEMPTS) {
                        chatAuth.remove(uuid);
                        player.kick(Component.text(
                                "qAuth: demasiados intentos fallidos.", NamedTextColor.RED));
                    } else {
                        player.sendMessage(Component.text(
                                "Contraseña incorrecta. Intentos restantes: "
                                        + (MAX_LOGIN_ATTEMPTS - attempts) + ".",
                                NamedTextColor.RED));
                    }
                }
            });
        });
        return true;
    }

    private void unlock(Player player, ChatSession session, String message) {
        chatAuth.remove(player.getUniqueId(), session);
        if (session.kickTask != null) {
            session.kickTask.cancel();
        }
        player.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    /** Mensaje de bienvenida post-registro (config.yml -> welcome). */
    private void sendWelcome(Player player) {
        if (!getConfig().getBoolean("welcome.enabled", true)) {
            return;
        }
        player.sendMessage(Component.text(
                "===============================================", NamedTextColor.GOLD));
        for (String line : getConfig().getStringList("welcome.lines")) {
            player.sendMessage(Component.text(line, NamedTextColor.YELLOW));
        }
        String url = getConfig().getString("welcome.discord-url", "");
        if (url != null && !url.isBlank()) {
            player.sendMessage(Component.text("Únete a nuestro Discord: ", NamedTextColor.GRAY)
                    .append(Component.text(url, NamedTextColor.AQUA)
                            .decoration(TextDecoration.UNDERLINED, true)
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    Component.text("Abrir enlace", NamedTextColor.GRAY)))));
        }
        player.sendMessage(Component.text(
                "===============================================", NamedTextColor.GOLD));
    }

    // ------------------------------------------------------------------
    // Diálogos
    // ------------------------------------------------------------------
    private Dialog registerDialog(String name, String error) {
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("qAuth - Registro", NamedTextColor.GOLD))
                        .canCloseWithEscape(false)
                        .body(body(error,
                                "Bienvenido, " + name + ". Crea tu cuenta para entrar.",
                                "Elige una contraseña que puedas recordar."))
                        .inputs(List.of(
                                textInput("password", "Contraseña"),
                                textInput("confirm", "Confirmar contraseña")))
                        .build())
                .type(DialogType.confirmation(
                        actionButton("Crear cuenta", KEY_REGISTER, NamedTextColor.GREEN),
                        actionButton("Salir", KEY_CANCEL, NamedTextColor.RED))));
    }

    private Dialog loginDialog(String name, String error) {
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("qAuth - Inicio de sesión", NamedTextColor.GOLD))
                        .canCloseWithEscape(false)
                        .body(body(error,
                                "Hola de nuevo, " + name + ".",
                                "Protocolo de acceso: si entras desde la misma IP que",
                                "en tu último acceso, entrarás directo sin contraseña.",
                                "Si tu IP cambió, el servidor te pedirá tu contraseña.",
                                "Introduce tu contraseña para entrar."))
                        .inputs(List.of(
                                textInput("password", "Contraseña")))
                        .build())
                .type(DialogType.confirmation(
                        actionButton("Entrar", KEY_LOGIN, NamedTextColor.GREEN),
                        actionButton("Salir", KEY_CANCEL, NamedTextColor.RED))));
    }

    /**
     * Diálogo de bienvenida mostrado tras registrarse.
     * inGame=false: fase de configuración (botones con callback).
     * inGame=true: dentro del mundo (botones que solo cierran).
     */
    private Dialog welcomeDialog(boolean inGame) {
        java.util.List<DialogBody> bodies = new java.util.ArrayList<>();
        for (String line : getConfig().getStringList("welcome.lines")) {
            bodies.add(DialogBody.plainMessage(Component.text(line, NamedTextColor.YELLOW)));
        }
        String url = getConfig().getString("welcome.discord-url", "");
        boolean hasUrl = url != null && !url.isBlank();
        if (hasUrl) {
            bodies.add(DialogBody.plainMessage(
                    Component.text("Discord: " + url, NamedTextColor.AQUA)));
        }
        if (bodies.isEmpty()) {
            bodies.add(DialogBody.plainMessage(
                    Component.text("Bienvenido.", NamedTextColor.YELLOW)));
        }

        ActionButton enter = inGame
                ? ActionButton.create(Component.text("Entrar", NamedTextColor.GREEN),
                        Component.text("Cerrar"), 120, null)
                : actionButton("Entrar", KEY_WELCOME, NamedTextColor.GREEN);

        ActionButton second;
        if (hasUrl) {
            second = ActionButton.builder(Component.text("Discord", NamedTextColor.AQUA))
                    .width(120)
                    .action(DialogAction.staticAction(ClickEvent.openUrl(url)))
                    .build();
        } else {
            second = inGame
                    ? ActionButton.create(Component.text("Cerrar", NamedTextColor.GRAY),
                            Component.text("Cerrar"), 120, null)
                    : actionButton("Cerrar", KEY_WELCOME, NamedTextColor.GRAY);
        }

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("qAuth - Bienvenido", NamedTextColor.GOLD))
                        .canCloseWithEscape(inGame)
                        .body(bodies)
                        .build())
                .type(DialogType.confirmation(enter, second)));
    }

    private static List<DialogBody> body(String error, String... infos) {
        java.util.ArrayList<DialogBody> list = new java.util.ArrayList<>();
        for (String info : infos) {
            list.add(DialogBody.plainMessage(Component.text(info, NamedTextColor.WHITE)));
        }
        if (error != null && !error.isEmpty()) {
            list.add(DialogBody.plainMessage(Component.text(error, NamedTextColor.RED)));
        }
        return list;
    }

    private static DialogInput textInput(String key, String label) {
        return DialogInput.text(key, Component.text(label, NamedTextColor.YELLOW))
                .width(200)
                .maxLength(MAX_PASSWORD_LENGTH)
                .labelVisible(true)
                .build();
    }

    private static ActionButton actionButton(String label, Key clickKey, TextColor color) {
        return ActionButton.builder(Component.text(label, color))
                .width(120)
                .action(DialogAction.customClick(clickKey, null))
                .build();
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------
    private static String normalizeIp(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            String host = inet.getAddress().getHostAddress();
            if (host.startsWith("::ffff:")) {
                host = host.substring(7);
            }
            return host;
        }
        return address != null ? address.toString() : "";
    }

    /**
     * Devuelve el protocolo del cliente vía ViaVersion (si está instalado).
     * -1 si no se puede determinar (se asume cliente moderno).
     */
    private int clientProtocol(UUID uuid) {
        if (uuid == null) {
            return -1;
        }
        try {
            Plugin viaPlugin = Bukkit.getPluginManager().getPlugin("ViaVersion");
            if (viaPlugin == null || !viaPlugin.isEnabled()) {
                return -1;
            }
            ClassLoader cl = viaPlugin.getClass().getClassLoader();
            Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via", true, cl);
            Object api = viaClass.getMethod("getAPI").invoke(null);
            return (int) api.getClass()
                    .getMethod("getPlayerVersion", UUID.class)
                    .invoke(api, uuid);
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
