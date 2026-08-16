package com.hxzhitang.tongdarailway.datagen;

import com.hxzhitang.tongdarailway.Tongdarailway;
import com.hxzhitang.tongdarailway.blocks.ModBlocks;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class ModLanguageProviderENUS extends LanguageProvider {
    public ModLanguageProviderENUS(PackOutput output) {
        super(output, Tongdarailway.MODID, "en_us");
    }

    @Override
    protected void addTranslations() {
        this.add(ModBlocks.TRACK_SPAWNER.get(), "Track Spawner");
        this.add("commands.tongdarailway.searchstation.already_searching", "A station search is already running.");
        this.add("commands.tongdarailway.searchstation.failed", "Station search failed. Check the server log for details.");
        this.add("commands.tongdarailway.searchstation.not_found", "No station was found in the surrounding %s x %s railway regions.");
        this.add("commands.tongdarailway.searchstation.searching", "Searching the surrounding %s x %s railway regions for the nearest station...");
        this.add("commands.tongdarailway.searchstation.success", "Nearest station coordinates: %s");
    }
}
