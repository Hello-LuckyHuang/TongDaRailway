package com.hxzhitang.tongdarailway.datagen;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.blocks.ModBlocks;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class ModLanguageProviderZHCN extends LanguageProvider {
    public ModLanguageProviderZHCN(PackOutput output) {
        super(output, Tongdarailway.MODID, "zh_cn");
    }

    @Override
    protected void addTranslations() {
        this.add(ModBlocks.TRACK_SPAWNER.get(), "¹ìµÀË¢¹ÖÁý");
    }
}
