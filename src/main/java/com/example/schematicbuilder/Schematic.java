package com.example.schematicbuilder;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Minimal .litematic reader (block types only). Coordinates are relative to the lowest corner. */
public class Schematic {
    public record Entry(int x, int y, int z, Block block) {}

    public final List<Entry> entries = new ArrayList<>();
    public int skipped = 0;

    public static Schematic load(Path path) throws IOException {
        NbtCompound root;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            root = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
        }

        Schematic result = new Schematic();
        NbtCompound regions = root.getCompoundOrEmpty("Regions");
        if (regions.getKeys().isEmpty()) throw new IOException("To nie wyglada na plik .litematic (brak 'Regions').");

        List<Entry> raw = new ArrayList<>();

        for (String regionName : regions.getKeys()) {
            NbtCompound region = regions.getCompoundOrEmpty(regionName);
            NbtCompound pos = region.getCompoundOrEmpty("Position");
            NbtCompound size = region.getCompoundOrEmpty("Size");

            int px = pos.getInt("x", 0), py = pos.getInt("y", 0), pz = pos.getInt("z", 0);
            int sx = size.getInt("x", 0), sy = size.getInt("y", 0), sz = size.getInt("z", 0);
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            if (ax == 0 || ay == 0 || az == 0) continue;

            int startX = sx < 0 ? px + sx + 1 : px;
            int startY = sy < 0 ? py + sy + 1 : py;
            int startZ = sz < 0 ? pz + sz + 1 : pz;

            NbtList paletteNbt = region.getListOrEmpty("BlockStatePalette");
            int paletteSize = paletteNbt.size();
            if (paletteSize == 0) continue;

            Block[] palette = new Block[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                String name = paletteNbt.getCompoundOrEmpty(i).getString("Name", "minecraft:air");
                Identifier id = Identifier.tryParse(name);
                palette[i] = id == null ? Blocks.AIR : Registries.BLOCK.get(id);
            }

            long[] states = region.getLongArray("BlockStates").orElse(new long[0]);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
            long volume = (long) ax * ay * az;
            if (volume > 60_000_000L) throw new IOException("Schemat jest zbyt duzy.");
            if ((volume * bits + 63) / 64 > states.length) throw new IOException("Uszkodzony plik (za malo danych BlockStates).");

            long mask = (1L << bits) - 1L;

            for (int i = 0; i < (int) volume; i++) {
                long startBit = (long) i * bits;
                int startArr = (int) (startBit >>> 6);
                int endArr = (int) ((startBit + bits - 1) >>> 6);
                int startOffset = (int) (startBit & 63L);

                int idx;
                if (startArr == endArr) {
                    idx = (int) ((states[startArr] >>> startOffset) & mask);
                } else {
                    int endOffset = 64 - startOffset;
                    idx = (int) (((states[startArr] >>> startOffset) | (states[endArr] << endOffset)) & mask);
                }
                if (idx >= paletteSize) continue;

                Block block = palette[idx];
                if (block.getDefaultState().isAir()) continue;
                if (block.asItem() == Items.AIR) {
                    result.skipped++;
                    continue;
                }

                int lx = i % ax;
                int lz = (i / ax) % az;
                int ly = i / (ax * az);
                raw.add(new Entry(startX + lx, startY + ly, startZ + lz, block));
            }
        }

        if (raw.isEmpty()) return result;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (Entry e : raw) {
            minX = Math.min(minX, e.x());
            minY = Math.min(minY, e.y());
            minZ = Math.min(minZ, e.z());
        }
        for (Entry e : raw) result.entries.add(new Entry(e.x() - minX, e.y() - minY, e.z() - minZ, e.block()));

        result.entries.sort(Comparator.comparingInt(Entry::y).thenComparingInt(Entry::x).thenComparingInt(Entry::z));
        return result;
    }
}
