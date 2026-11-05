package com.dozamigos.updater.client;

import com.dozamigos.updater.Updater;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class UpdateScreen extends Screen {
    private enum State { CHECKING, UP_TO_DATE, PROMPT, DOWNLOADING, DONE, ERROR }

    private static final int MAX_LINES = 7;
    private static final int PANEL_W = 300;
    private static final int TEXT = 0xFF303030;
    private static final int GREEN = 0xFF1E7B1E;
    private static final int RED = 0xFFAA0000;

    private final Screen parent;
    private volatile Updater.Plan plan;
    private final List<Component> lines = new ArrayList<>();
    private int added, changed, removed;

    private volatile State state;
    private volatile String currentFile = "";
    private volatile int index, total;
    private volatile long done, overall;
    private volatile String error = "";
    private State builtFor;
    private boolean downloadFailed;

    /** Abre ja com as mudancas encontradas (verificacao automatica ao iniciar). */
    public UpdateScreen(Screen parent, Updater.Plan plan) {
        super(Component.literal("Atualização do Modpack"));
        this.parent = parent;
        setPlan(plan);
        state = State.PROMPT;
    }

    /** Abre verificando sozinho (botao do menu). */
    public UpdateScreen(Screen parent) {
        super(Component.literal("Atualização do Modpack"));
        this.parent = parent;
        state = State.CHECKING;
        Thread t = new Thread(() -> {
            try {
                Updater.Plan p = Updater.check();
                if (p == null) {
                    fail("Repositório não configurado em config/dozamigosupdater.properties");
                } else if (p.isEmpty()) {
                    state = State.UP_TO_DATE;
                } else {
                    setPlan(p);
                    state = State.PROMPT;
                }
            } catch (Exception e) {
                fail("Não foi possível acessar o GitHub");
            }
        }, "dozamigos-updater-manual-check");
        t.setDaemon(true);
        t.start();
    }

    private void fail(String msg) {
        error = msg;
        downloadFailed = false;
        state = State.ERROR;
    }

    private synchronized void setPlan(Updater.Plan p) {
        plan = p;
        lines.clear();
        added = changed = removed = 0;
        for (Updater.Item it : p.download) {
            boolean exists = Files.exists(Path.of("mods", it.name()));
            if (exists) changed++; else added++;
            lines.add(Component.literal(exists ? "~ " : "+ ").withStyle(exists ? ChatFormatting.YELLOW : ChatFormatting.GREEN)
                    .append(Component.literal(it.name()).withStyle(ChatFormatting.WHITE)));
        }
        for (String name : p.delete) {
            removed++;
            lines.add(Component.literal("- ").withStyle(ChatFormatting.RED)
                    .append(Component.literal(name).withStyle(ChatFormatting.GRAY)));
        }
    }

    @Override
    protected void init() {
        builtFor = state;
        int cx = width / 2;
        int y = height / 2 + 70;
        switch (state) {
            case PROMPT -> {
                addRenderableWidget(Button.builder(Component.literal("Atualizar agora"), b -> startDownload())
                        .bounds(cx - 102, y, 100, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Agora não"), b -> onClose())
                        .bounds(cx + 2, y, 100, 20).build());
            }
            case UP_TO_DATE, CHECKING -> addRenderableWidget(Button.builder(Component.literal(state == State.CHECKING ? "Cancelar" : "Voltar"), b -> onClose())
                    .bounds(cx - 50, y, 100, 20).build());
            case DONE -> addRenderableWidget(Button.builder(Component.literal("Fechar o Minecraft"), b -> Minecraft.getInstance().stop())
                    .bounds(cx - 100, y, 200, 20).build());
            case ERROR -> {
                addRenderableWidget(Button.builder(Component.literal("Tentar de novo"), b -> retry())
                        .bounds(cx - 102, y, 100, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Voltar"), b -> onClose())
                        .bounds(cx + 2, y, 100, 20).build());
            }
            default -> { }
        }
    }

    private void retry() {
        if (downloadFailed && plan != null) {
            startDownload();
        } else {
            Minecraft.getInstance().gui.setScreen(new UpdateScreen(parent));
        }
    }

    private void startDownload() {
        state = State.DOWNLOADING;
        done = 0;
        Thread t = new Thread(() -> {
            try {
                Updater.apply(plan, (file, i, n, d, all) -> {
                    currentFile = file;
                    index = i;
                    total = n;
                    done = d;
                    overall = all;
                });
                state = State.DONE;
            } catch (Exception e) {
                error = "Falha ao baixar os mods. Verifique a internet.";
                downloadFailed = true;
                state = State.ERROR;
            }
        }, "dozamigos-updater-download");
        t.setDaemon(true);
        t.start();
        rebuildWidgets();
    }

    @Override
    public void tick() {
        if (builtFor != state) rebuildWidgets();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return state != State.DOWNLOADING;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    // O painel vai no fundo para nao cobrir os botoes, que sao desenhados depois.
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);

        int left = width / 2 - PANEL_W / 2;
        int top = height / 2 - 90;
        int bottom = height / 2 + 100;

        // Painel estilo inventario: borda preta, bevel claro/escuro e fundo cinza
        g.fill(left - 2, top - 2, left + PANEL_W + 2, bottom + 2, 0xFF000000);
        g.fill(left, top, left + PANEL_W, bottom, 0xFFC6C6C6);
        g.fill(left, top, left + PANEL_W, top + 2, 0xFFFFFFFF);
        g.fill(left, top, left + 2, bottom, 0xFFFFFFFF);
        g.fill(left, bottom - 2, left + PANEL_W, bottom, 0xFF555555);
        g.fill(left + PANEL_W - 2, top, left + PANEL_W, bottom, 0xFF555555);
    }

    // Texto sem sombra: no painel claro a sombra deixava as letras borradas.
    private void center(GuiGraphicsExtractor g, String s, int y, int color) {
        g.text(font, s, width / 2 - font.width(s) / 2, y, color, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        int cx = width / 2;
        int left = cx - PANEL_W / 2;
        int top = height / 2 - 90;

        center(g, "Dozamigos Modpack", top + 10, TEXT);

        switch (state) {
            case CHECKING -> center(g, "Verificando atualizações...", top + 80, TEXT);
            case UP_TO_DATE -> {
                center(g, "Tudo certo!", top + 70, GREEN);
                center(g, "Seu modpack já está na versão mais recente.", top + 90, TEXT);
            }
            case PROMPT -> {
                center(g, "Nova atualização disponível", top + 26, GREEN);
                center(g, summary(), top + 40, TEXT);
                drawList(g, left + 12, top + 56);
            }
            case DOWNLOADING -> {
                center(g, "Baixando atualização...", top + 34, TEXT);
                center(g, currentFile, top + 54, TEXT);
                center(g, "Arquivo " + index + " de " + total, top + 66, 0xFF555555);
                float pct = overall == 0 ? 0 : (float) done / overall;
                drawBar(g, left + 20, top + 86, PANEL_W - 40, pct);
                center(g, (int) (pct * 100) + "%  -  " + mb(done) + " de " + mb(overall), top + 104, TEXT);
                center(g, "Não feche o jogo durante o download.", top + 130, RED);
            }
            case DONE -> {
                center(g, "Atualização concluída!", top + 44, GREEN);
                drawBar(g, left + 20, top + 70, PANEL_W - 40, 1f);
                center(g, "Feche o Minecraft e abra novamente", top + 100, TEXT);
                center(g, "para jogar com os mods atualizados.", top + 112, TEXT);
            }
            case ERROR -> {
                center(g, "Algo deu errado", top + 56, RED);
                center(g, error, top + 80, TEXT);
            }
        }
    }

    private String summary() {
        List<String> parts = new ArrayList<>();
        if (added > 0) parts.add(added + (added == 1 ? " novo" : " novos"));
        if (changed > 0) parts.add(changed + (changed == 1 ? " atualizado" : " atualizados"));
        if (removed > 0) parts.add(removed + (removed == 1 ? " removido" : " removidos"));
        return String.join("  -  ", parts);
    }

    private synchronized void drawList(GuiGraphicsExtractor g, int x, int y) {
        boolean overflow = lines.size() > MAX_LINES;
        int shown = overflow ? MAX_LINES - 1 : lines.size();
        g.fill(x - 2, y - 2, x + 276 + 2, y + MAX_LINES * 12 + 2, 0xFF373737);
        g.fill(x, y, x + 276, y + MAX_LINES * 12, 0xFF8B8B8B);
        for (int i = 0; i < shown; i++) {
            g.text(font, lines.get(i), x + 4, y + 2 + i * 12, 0xFFFFFFFF, true);
        }
        if (overflow) {
            g.text(font, Component.literal("... e mais " + (lines.size() - shown)), x + 4, y + 2 + shown * 12, 0xFFFFFFFF, true);
        }
    }

    // Barra de XP: moldura preta, fundo escuro e preenchimento verde em blocos
    private void drawBar(GuiGraphicsExtractor g, int x, int y, int w, float pct) {
        pct = Math.max(0f, Math.min(1f, pct));
        g.fill(x - 2, y - 2, x + w + 2, y + 12, 0xFF000000);
        g.fill(x, y, x + w, y + 10, 0xFF373737);
        int fill = (int) (w * pct);
        g.fill(x, y, x + fill, y + 10, 0xFF55FF55);
        g.fill(x, y, x + fill, y + 3, 0xFF9BFF9B);
        g.fill(x, y + 7, x + fill, y + 10, 0xFF2E9E2E);
        for (int sx = x + 8; sx < x + w; sx += 8) {
            g.fill(sx, y, sx + 1, y + 10, 0x66000000);
        }
    }

    private static String mb(long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }
}
