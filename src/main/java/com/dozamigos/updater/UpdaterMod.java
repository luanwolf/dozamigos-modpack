package com.dozamigos.updater;

import com.dozamigos.updater.client.ClientHooks;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(UpdaterMod.MODID)
public final class UpdaterMod {
    public static final String MODID = "dozamigosupdater";
    private static final Logger LOGGER = LogUtils.getLogger();

    public UpdaterMod(FMLJavaModLoadingContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientHooks.init();
            return;
        }
        try {
            Updater.runHeadless();
        } catch (Exception e) {
            // Sem internet / GitHub fora do ar nunca pode impedir o servidor de abrir.
            LOGGER.error("[{}] Falha ao sincronizar o modpack, seguindo com os mods atuais", MODID, e);
        }
    }
}
