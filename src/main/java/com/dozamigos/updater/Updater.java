package com.dozamigos.updater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

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

public final class Updater {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CONFIG = Path.of("config", "dozamigosupdater.properties");
    private static final Path MANAGED = Path.of("config", "dozamigosupdater.managed");
    private static final Path MODS = Path.of("mods");
    private static final String TMP_SUFFIX = ".dzupdate";

    private Updater() {}

    public record Item(String name, String sha, long size) {}

    public interface Progress {
        void update(String file, int index, int total, long done, long overall);
    }

    public static final class Plan {
        String repo, branch, dir, token;
        boolean exit;
        Map<String, Item> remote;
        public List<Item> download = new ArrayList<>();
        public List<String> delete = new ArrayList<>();

        public boolean isEmpty() {
            return download.isEmpty() && delete.isEmpty();
        }

        long totalBytes() {
            return download.stream().mapToLong(Item::size).sum();
        }
    }

    /** Compara o GitHub com a pasta mods. Retorna null se o repo nao estiver configurado. */
    public static Plan check() throws Exception {
        Properties cfg = loadConfig();
        Plan p = new Plan();
        p.repo = cfg.getProperty("repo", "").trim();
        if (p.repo.isEmpty() || p.repo.equals("OWNER/REPO")) {
            LOGGER.warn("Defina 'repo' em {} para ativar o auto-update", CONFIG);
            return null;
        }
        p.branch = cfg.getProperty("branch", "main").trim();
        p.dir = cfg.getProperty("path", "mods").trim().replaceAll("^/+|/+$", "");
        p.token = cfg.getProperty("token", "").trim();
        p.exit = Boolean.parseBoolean(cfg.getProperty("exitAfterUpdate", "true"));

        p.remote = fetchRemote(p.repo, p.branch, p.dir, p.token);
        String self = selfJarName();
        p.remote.remove(self);

        Files.createDirectories(MODS);
        for (Item it : p.remote.values()) {
            Path local = MODS.resolve(it.name());
            if (!Files.exists(local) || !gitBlobSha(local).equals(it.sha())) {
                p.download.add(it);
            }
        }
        for (String name : readManaged()) {
            if (!p.remote.containsKey(name) && !name.equals(self) && Files.exists(MODS.resolve(name))) {
                p.delete.add(name);
            }
        }
        if (p.isEmpty()) {
            writeManaged(p.remote.keySet());
            LOGGER.info("Modpack ja esta atualizado ({} mods)", p.remote.size());
        }
        return p;
    }

    /** Baixa e aplica o plano. Em caso de erro, remove os temporarios e repassa a excecao. */
    public static void apply(Plan p, Progress progress) throws Exception {
        long overall = p.totalBytes();
        long[] done = {0};
        try {
            int i = 0;
            for (Item it : p.download) {
                i++;
                LOGGER.info("Baixando {}", it.name());
                int idx = i;
                download(p, it, MODS.resolve(it.name() + TMP_SUFFIX), n -> {
                    done[0] += n;
                    if (progress != null) progress.update(it.name(), idx, p.download.size(), done[0], overall);
                });
            }
        } catch (Exception e) {
            for (Item it : p.download) Files.deleteIfExists(MODS.resolve(it.name() + TMP_SUFFIX));
            throw e;
        }

        // Em Windows um jar carregado fica travado: o que falhar aqui e aplicado depois que o jogo fechar.
        List<String> deferred = new ArrayList<>();
        for (Item it : p.download) {
            Path tmp = MODS.resolve(it.name() + TMP_SUFFIX);
            Path target = MODS.resolve(it.name());
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                deferred.add("Move-Item -LiteralPath " + q(tmp) + " -Destination " + q(target) + " -Force");
            }
        }
        for (String name : p.delete) {
            Path target = MODS.resolve(name);
            try {
                Files.deleteIfExists(target);
            } catch (IOException ex) {
                deferred.add("Remove-Item -LiteralPath " + q(target) + " -Force");
            }
        }
        writeManaged(p.remote.keySet());
        if (!deferred.isEmpty()) {
            spawnDeferred(deferred);
        }
    }

    /** Servidor dedicado: sem interface, aplica direto e fecha. */
    static void runHeadless() throws Exception {
        Plan p = check();
        if (p == null || p.isEmpty()) return;
        apply(p, null);
        LOGGER.warn("Modpack atualizado ({} baixado(s), {} removido(s)). Reinicie o servidor.", p.download.size(), p.delete.size());
        if (p.exit) Runtime.getRuntime().halt(0);
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

    private static Map<String, Item> fetchRemote(String repo, String branch, String dir, String token) throws IOException {
        String url = "https://api.github.com/repos/" + repo + "/git/trees/" + branch + "?recursive=1";
        JsonObject root;
        try (InputStream in = open(url, token, "application/vnd.github+json")) {
            root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        if (root.has("truncated") && root.get("truncated").getAsBoolean()) {
            LOGGER.warn("Lista do GitHub truncada, alguns mods podem ser ignorados");
        }
        String prefix = dir.isEmpty() ? "" : dir + "/";
        Map<String, Item> out = new LinkedHashMap<>();
        JsonArray tree = root.getAsJsonArray("tree");
        for (JsonElement el : tree) {
            JsonObject o = el.getAsJsonObject();
            String path = o.get("path").getAsString();
            if (!"blob".equals(o.get("type").getAsString()) || !path.startsWith(prefix)) continue;
            String name = path.substring(prefix.length());
            if (name.contains("/") || !name.endsWith(".jar")) continue;
            out.put(name, new Item(name, o.get("sha").getAsString(), o.has("size") ? o.get("size").getAsLong() : 0));
        }
        return out;
    }

    private static void download(Plan p, Item item, Path dest, java.util.function.LongConsumer onBytes) throws IOException {
        String url;
        String accept = null;
        if (p.token.isEmpty()) {
            String path = "/" + p.repo + "/" + p.branch + "/" + (p.dir.isEmpty() ? "" : p.dir + "/") + item.name();
            try {
                url = new URI("https", "raw.githubusercontent.com", path, null).toASCIIString();
            } catch (Exception e) {
                throw new IOException(e);
            }
        } else {
            url = "https://api.github.com/repos/" + p.repo + "/git/blobs/" + item.sha();
            accept = "application/vnd.github.raw+json";
        }
        try (InputStream in = open(url, p.token, accept); var out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                onBytes.accept(n);
            }
        }
        if (!gitBlobSha(dest).equals(item.sha())) {
            Files.deleteIfExists(dest);
            throw new IOException("Hash invalido ao baixar " + item.name());
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
