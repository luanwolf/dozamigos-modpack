package com.dozamigos.updater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class Updater {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CONFIG = Path.of("config", "dozamigosupdater.properties");
    private static final Path MANAGED = Path.of("config", "dozamigosupdater.managed");
    private static final Path MODS = Path.of("mods");
    private static final String TMP_SUFFIX = ".dzupdate";

    private Updater() {}

    static void run() throws Exception {
        Properties cfg = loadConfig();
        String repo = cfg.getProperty("repo", "").trim();
        if (repo.isEmpty() || repo.equals("OWNER/REPO")) {
            LOGGER.warn("Defina 'repo' em {} para ativar o auto-update", CONFIG);
            return;
        }
        String branch = cfg.getProperty("branch", "main").trim();
        String dir = cfg.getProperty("path", "mods").trim().replaceAll("^/+|/+$", "");
        String token = cfg.getProperty("token", "").trim();
        boolean exit = Boolean.parseBoolean(cfg.getProperty("exitAfterUpdate", "true"));

        Map<String, String> remote = fetchRemote(repo, branch, dir, token);
        String self = selfJarName();
        remote.remove(self);

        Set<String> managed = readManaged();
        Files.createDirectories(MODS);

        Map<String, String> toDownload = new LinkedHashMap<>();
        for (var e : remote.entrySet()) {
            Path local = MODS.resolve(e.getKey());
            if (!Files.exists(local) || !gitBlobSha(local).equals(e.getValue())) {
                toDownload.put(e.getKey(), e.getValue());
            }
        }
        List<String> toDelete = new ArrayList<>();
        for (String name : managed) {
            if (!remote.containsKey(name) && !name.equals(self) && Files.exists(MODS.resolve(name))) {
                toDelete.add(name);
            }
        }

        if (toDownload.isEmpty() && toDelete.isEmpty()) {
            writeManaged(remote.keySet());
            LOGGER.info("Modpack ja esta atualizado ({} mods)", remote.size());
            return;
        }

        for (var e : toDownload.entrySet()) {
            LOGGER.info("Baixando {}", e.getKey());
            download(repo, branch, dir, e.getKey(), e.getValue(), token, MODS.resolve(e.getKey() + TMP_SUFFIX));
        }

        // Em Windows um jar carregado fica travado: o que falhar aqui e aplicado depois que o jogo fechar.
        List<String> deferred = new ArrayList<>();
        for (String name : toDownload.keySet()) {
            Path tmp = MODS.resolve(name + TMP_SUFFIX);
            Path target = MODS.resolve(name);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                deferred.add("Move-Item -LiteralPath " + q(tmp) + " -Destination " + q(target) + " -Force");
            }
        }
        for (String name : toDelete) {
            Path target = MODS.resolve(name);
            try {
                Files.deleteIfExists(target);
            } catch (IOException ex) {
                deferred.add("Remove-Item -LiteralPath " + q(target) + " -Force");
            }
        }
        writeManaged(remote.keySet());
        if (!deferred.isEmpty()) {
            spawnDeferred(deferred);
        }

        String msg = "Modpack atualizado: " + toDownload.size() + " baixado(s)/atualizado(s), "
                + toDelete.size() + " removido(s).\nReabra o Minecraft para aplicar as mudancas.";
        LOGGER.warn(msg);
        if (exit) {
            if (!GraphicsEnvironment.isHeadless()) {
                try {
                    javax.swing.JOptionPane.showMessageDialog(null, msg, "Dozamigos Modpack", javax.swing.JOptionPane.INFORMATION_MESSAGE);
                } catch (Throwable ignored) {
                }
            }
            Runtime.getRuntime().halt(0);
        }
    }

    private static Properties loadConfig() throws IOException {
        Properties p = new Properties();
        if (!Files.exists(CONFIG)) {
            Files.createDirectories(CONFIG.getParent());
            Files.writeString(CONFIG, """
                    # Repositorio GitHub (dono/nome) que contem os jars do modpack
                    repo=luanwolf/dozamigos-modpack
                    branch=main
                    # Pasta dentro do repositorio com os .jar
                    path=mods
                    # Opcional: token do GitHub (necessario para repositorio privado)
                    token=
                    # Fecha o jogo apos atualizar, pois os mods novos so carregam no proximo inicio
                    exitAfterUpdate=true
                    """, StandardCharsets.UTF_8);
        }
        try (var in = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p;
    }

    private static Map<String, String> fetchRemote(String repo, String branch, String dir, String token) throws IOException {
        String url = "https://api.github.com/repos/" + repo + "/git/trees/" + branch + "?recursive=1";
        JsonObject root;
        try (InputStream in = open(url, token, "application/vnd.github+json")) {
            root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        if (root.has("truncated") && root.get("truncated").getAsBoolean()) {
            LOGGER.warn("Lista do GitHub truncada, alguns mods podem ser ignorados");
        }
        String prefix = dir.isEmpty() ? "" : dir + "/";
        Map<String, String> out = new LinkedHashMap<>();
        JsonArray tree = root.getAsJsonArray("tree");
        for (JsonElement el : tree) {
            JsonObject o = el.getAsJsonObject();
            String path = o.get("path").getAsString();
            if (!"blob".equals(o.get("type").getAsString()) || !path.startsWith(prefix)) continue;
            String name = path.substring(prefix.length());
            if (name.contains("/") || !name.endsWith(".jar")) continue;
            out.put(name, o.get("sha").getAsString());
        }
        return out;
    }

    private static void download(String repo, String branch, String dir, String name, String sha, String token, Path dest) throws IOException {
        String url;
        String accept = null;
        if (token.isEmpty()) {
            String path = "/" + repo + "/" + branch + "/" + (dir.isEmpty() ? "" : dir + "/") + name;
            try {
                url = new URI("https", "raw.githubusercontent.com", path, null).toASCIIString();
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            url = "https://api.github.com/repos/" + repo + "/git/blobs/" + sha;
            accept = "application/vnd.github.raw+json";
        }
        try (InputStream in = open(url, token, accept)) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!gitBlobSha(dest).equals(sha)) {
            Files.deleteIfExists(dest);
            throw new IOException("Hash invalido ao baixar " + name);
        }
    }

    private static InputStream open(String url, String token, String accept) throws IOException {
        URLConnection c = URI.create(url).toURL().openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("User-Agent", "dozamigos-updater");
        if (accept != null) c.setRequestProperty("Accept", accept);
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        return c.getInputStream();
    }

    private static String gitBlobSha(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        md.update(("blob " + Files.size(file) + "\0").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String selfJarName() {
        try {
            Path p = Path.of(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return p.getFileName().toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static Set<String> readManaged() throws IOException {
        Set<String> s = new LinkedHashSet<>();
        if (Files.exists(MANAGED)) s.addAll(Files.readAllLines(MANAGED, StandardCharsets.UTF_8));
        s.remove("");
        return s;
    }

    private static void writeManaged(Set<String> names) throws IOException {
        Files.createDirectories(MANAGED.getParent());
        Files.write(MANAGED, names, StandardCharsets.UTF_8);
    }

    private static String q(Path p) {
        return "'" + p.toAbsolutePath().toString().replace("'", "''") + "'";
    }

    private static void spawnDeferred(List<String> ops) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            LOGGER.error("Nao foi possivel aplicar algumas mudancas; aplique manualmente os arquivos *{}", TMP_SUFFIX);
            return;
        }
        String script = "Wait-Process -Id " + ProcessHandle.current().pid() + " -ErrorAction SilentlyContinue; Start-Sleep 2; "
                + String.join("; ", ops);
        new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script).start();
    }
}
