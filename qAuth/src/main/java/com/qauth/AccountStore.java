package com.qauth;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Persistencia de cuentas en plugins/qAuth/accounts.yml.
 * Formato:
 *   users:
 *     nombre:
 *       ip: "1.2.3.4"
 *       salt: <base64>
 *       hash: <base64>
 *       last-login: 1234567890
 */
public final class AccountStore {

    public record Account(String ip, String salt, String hash, long lastLogin) {
    }

    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final File file;
    private final Map<String, Account> accounts = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public AccountStore(File file) {
        this.file = file;
    }

    public synchronized void load() {
        accounts.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection users = yaml.getConfigurationSection("users");
        if (users == null) {
            return;
        }
        for (String name : users.getKeys(false)) {
            ConfigurationSection s = users.getConfigurationSection(name);
            if (s == null) {
                continue;
            }
            accounts.put(name.toLowerCase(Locale.ROOT), new Account(
                    s.getString("ip", ""),
                    s.getString("salt", ""),
                    s.getString("hash", ""),
                    s.getLong("last-login", 0)));
        }
    }

    public synchronized void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<String, Account> e : accounts.entrySet()) {
                String path = "users." + e.getKey();
                yaml.set(path + ".ip", e.getValue().ip());
                yaml.set(path + ".salt", e.getValue().salt());
                yaml.set(path + ".hash", e.getValue().hash());
                yaml.set(path + ".last-login", e.getValue().lastLogin());
            }
            yaml.save(file);
        } catch (IOException ex) {
            // no lanzar: el auth no debe romper el join por un fallo de disco
        }
    }

    public synchronized Optional<Account> get(String name) {
        return Optional.ofNullable(accounts.get(name.toLowerCase(Locale.ROOT)));
    }

    public synchronized boolean contains(String name) {
        return accounts.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public synchronized int size() {
        return accounts.size();
    }

    /** Crea la cuenta con la IP actual y la contraseña indicada. */
    public synchronized void register(String name, String ip, String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        String hash = hash(password, salt);
        accounts.put(name.toLowerCase(Locale.ROOT), new Account(
                ip,
                Base64.getEncoder().encodeToString(salt),
                hash,
                System.currentTimeMillis()));
        save();
    }

    /** Verifica la contraseña. Devuelve true si coincide. */
    public synchronized boolean verifyPassword(String name, String password) {
        Account acc = accounts.get(name.toLowerCase(Locale.ROOT));
        if (acc == null || acc.salt().isEmpty() || acc.hash().isEmpty()) {
            return false;
        }
        byte[] salt;
        try {
            salt = Base64.getDecoder().decode(acc.salt());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String candidate = hash(password, salt);
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                acc.hash().getBytes(StandardCharsets.UTF_8));
    }

    /** Actualiza la IP guardada y el último login tras un acceso correcto. */
    public synchronized void touchLogin(String name, String ip) {
        String key = name.toLowerCase(Locale.ROOT);
        Account acc = accounts.get(key);
        if (acc == null) {
            return;
        }
        accounts.put(key, new Account(ip, acc.salt(), acc.hash(), System.currentTimeMillis()));
        save();
    }

    private static String hash(String password, byte[] salt) {
        try {
            javax.crypto.SecretKeyFactory factory =
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    password.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_BITS);
            byte[] derived = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(derived);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("PBKDF2 no disponible", ex);
        }
    }
}
