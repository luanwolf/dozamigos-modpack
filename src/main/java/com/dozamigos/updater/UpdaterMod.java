package com.dozamigos.updater;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(UpdaterMod.MODID)
public final class UpdaterMod {
    public static final String MODID = "dozamigosupdater";
    private static final Logger LOGGER = LogUtils.getLogger();

    public UpdaterMod(FMLJavaModLoadingContext context) {
        try {
            Updater.run();
        } catch (Exception e) {
            // Sem internet / GitHub fora do ar nunca pode impedir o jogo de abrir.
            LOGGER.error("[{}] Falha ao sincronizar o modpack, seguindo com os mods atuais", MODID, e);
        }
    }
}
