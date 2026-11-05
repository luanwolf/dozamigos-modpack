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
    private enum State { PROMPT, DOWNLOADING, DONE, ERROR }

    private static final int MAX_LINES = 7;
    private static final int WHITE = 0xFFFFFFFF;

    private final Screen parent;
    private final Updater.Plan plan;
    private final List<Component> lines = new ArrayList<>();

    private volatile State state = State.PROMPT;
    private volatile String currentFile = "";
    private volatile int index, total;
    private volatile long done, overall;
    private volatile String error = "";
    private State builtFor;

    public UpdateScreen(Screen parent, Updater.Plan plan) {
        super(Component.literal("Atualização do Modpack"));
        this.parent = parent;
        this.plan = plan;
        for (Updater.Item it : plan.download) {
            boolean exists = Files.exists(Path.of("mods", it.name()));
            lines.add(Component.literal(exists ? "~ " : "+ ").withStyle(exists ? ChatFormatting.YELLOW : ChatFormatting.GREEN)
                    .append(Component.literal(it.name()).withStyle(ChatFormatting.WHITE)));
        }
        for (String name : plan.delete) {
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
            case DONE -> addRenderableWidget(Button.builder(Component.literal("Fechar o jogo"), b -> Minecraft.getInstance().stop())
                    .bounds(cx - 100, y, 200, 20).build());
            case ERROR -> {
                addRenderableWidget(Button.builder(Component.literal("Tentar de novo"), b -> startDownload())
                        .bounds(cx - 102, y, 100, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Continuar assim"), b -> onClose())
                        .bounds(cx + 2, y, 100, 20).build());
            }
            default -> { }
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
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
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
        return state == State.PROMPT || state == State.ERROR;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    // O painel vai no fundo para nao cobrir os botoes, que sao desenhados depois.
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);

        int panelW = 300;
        int left = width / 2 - panelW / 2;
        int top = height / 2 - 90;
        int bottom = height / 2 + 100;

        // Painel estilo inventario: borda preta, bevel claro/escuro e fundo cinza
        g.fill(left - 2, top - 2, left + panelW + 2, bottom + 2, 0xFF000000);
        g.fill(left, top, left + panelW, bottom, 0xFFC6C6C6);
        g.fill(left, top, left + panelW, top + 2, 0xFFFFFFFF);
        g.fill(left, top, left + 2, bottom, 0xFFFFFFFF);
        g.fill(left, bottom - 2, left + panelW, bottom, 0xFF555555);
        g.fill(left + panelW - 2, top, left + panelW, bottom, 0xFF555555);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        int cx = width / 2;
        int panelW = 300;
        int left = cx - panelW / 2;
        int top = height / 2 - 90;

        g.centeredText(font, Component.literal("Dozamigos Modpack").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD), cx, top + 8, 0xFF404040);

        switch (state) {
            case PROMPT -> {
                g.centeredText(font, Component.literal("Há uma atualização disponível!").withStyle(ChatFormatting.DARK_GREEN), cx, top + 24, 0xFF00AA00);
                drawList(g, left + 12, top + 42);
            }
            case DOWNLOADING -> {
                g.centeredText(font, Component.literal("Baixando mods...").withStyle(ChatFormatting.DARK_GRAY), cx, top + 30, 0xFF404040);
                g.centeredText(font, Component.literal(currentFile + " (" + index + "/" + total + ")"), cx, top + 50, 0xFF202020);
                drawBar(g, left + 20, top + 80, panelW - 40, overall == 0 ? 0 : (float) done / overall);
                g.centeredText(font, Component.literal(mb(done) + " / " + mb(overall)), cx, top + 98, 0xFF404040);
                g.centeredText(font, Component.literal("Não feche o jogo").withStyle(ChatFormatting.DARK_RED), cx, top + 125, 0xFFAA0000);
            }
            case DONE -> {
                g.centeredText(font, Component.literal("Modpack atualizado!").withStyle(ChatFormatting.DARK_GREEN), cx, top + 40, 0xFF00AA00);
                drawBar(g, left + 20, top + 80, panelW - 40, 1f);
                g.centeredText(font, Component.literal("Abra o Minecraft novamente para jogar com a nova versão."), cx, top + 105, 0xFF202020);
            }
            case ERROR -> {
                g.centeredText(font, Component.literal("Falha na atualização").withStyle(ChatFormatting.DARK_RED), cx, top + 40, 0xFFAA0000);
                g.centeredText(font, Component.literal(error), cx, top + 65, 0xFF202020);
                g.centeredText(font, Component.literal("Verifique a internet e tente de novo."), cx, top + 85, 0xFF404040);
            }
        }
    }

    private void drawList(GuiGraphicsExtractor g, int x, int y) {
        boolean overflow = lines.size() > MAX_LINES;
        int shown = overflow ? MAX_LINES - 1 : lines.size();
        g.fill(x - 2, y - 2, x + 276 + 2, y + MAX_LINES * 12 + 2, 0xFF373737);
        g.fill(x, y, x + 276, y + MAX_LINES * 12, 0xFF8B8B8B);
        for (int i = 0; i < shown; i++) {
            g.text(font, lines.get(i), x + 4, y + 2 + i * 12, WHITE, true);
        }
        if (overflow) {
            g.text(font, Component.literal("... e mais " + (lines.size() - shown)), x + 4, y + 2 + shown * 12, WHITE, true);
        }
        g.centeredText(font, Component.literal("+ novo   ~ atualizado   - removido").withStyle(ChatFormatting.DARK_GRAY), width / 2, y + MAX_LINES * 12 + 8, 0xFF404040);
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
