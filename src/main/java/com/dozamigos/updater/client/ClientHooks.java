package com.dozamigos.updater.client;

import com.dozamigos.updater.Updater;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.event.ScreenEvent;
import org.slf4j.Logger;

public final class ClientHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Updater.Plan pending;
    private static boolean shown;

    private ClientHooks() {}

    public static void init() {
        ScreenEvent.Opening.BUS.addListener(ClientHooks::onOpening);
        Thread t = new Thread(() -> {
            try {
                Updater.Plan p = Updater.check();
                if (p == null || p.isEmpty()) return;
                pending = p;
                // Se o titulo ja abriu antes do check terminar, mostra por cima dele
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    if (mc.gui.screen() instanceof TitleScreen title) show(title);
                });
            } catch (Exception e) {
                // Sem internet / GitHub fora do ar nunca pode impedir o jogo de abrir.
                LOGGER.error("Falha ao verificar o modpack", e);
            }
        }, "dozamigos-updater-check");
        t.setDaemon(true);
        t.start();
    }

    private static boolean onOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof TitleScreen title && pending != null && !shown) {
            shown = true;
            event.setNewScreen(new UpdateScreen(title, pending));
        }
        return false;
    }

    private static void show(TitleScreen title) {
        if (shown) return;
        shown = true;
        Minecraft.getInstance().gui.setScreen(new UpdateScreen(title, pending));
    }
}
