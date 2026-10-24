package com.example.schematicbuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class Builder {
    private static final int SCAN_BUDGET = 6000;
    private static final int SPARE_HOTBAR_SLOT = 8;

    private final MinecraftClient mc = MinecraftClient.getInstance();

    public double range = 4.0;
    public int blocksPerTick = 1;
    public int delay = 1;
    public int maxAttempts = 3;

    private Schematic schematic;
    private String loadedName = "";
    private BlockPos origin;
    private boolean running;
    private boolean[] done;
    private int[] attempts;
    private int first;
    private int doneCount;
    private int timer;
    private final Set<Block> warned = new HashSet<>();

    public static Path schematicsDir() {
        return MinecraftClient.getInstance().runDirectory.toPath().resolve("schematics").normalize();
    }

    public static List<String> listFiles() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(schematicsDir())) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.toLowerCase().endsWith(".litematic")) out.add(n.substring(0, n.length() - ".litematic".length()));
            });
        } catch (Exception ignored) {
        }
        return out;
    }

    public void msg(String text) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[SchemBuilder] " + text), false);
    }

    public void load(String name) {
        stop(false);
        if (mc.player == null) return;

        name = name.trim();
        if (!name.toLowerCase().endsWith(".litematic")) name += ".litematic";

        Path dir = schematicsDir();
        Path path = dir.resolve(name).normalize();
        if (!path.startsWith(dir)) {
            msg("Niedozwolona sciezka pliku.");
            return;
        }
        if (!Files.isRegularFile(path)) {
            msg("Nie znaleziono pliku: " + path);
            return;
        }

        Schematic loaded;
        try {
            loaded = Schematic.load(path);
        } catch (IOException | RuntimeException e) {
            msg("Nie udalo sie wczytac schematu: " + e.getMessage());
            return;
        }

        if (loaded.entries.isEmpty()) {
            msg("Schemat nie zawiera zadnych blokow do postawienia.");
            return;
        }

        schematic = loaded;
        loadedName = name;
        origin = mc.player.getBlockPos();
        resetProgress();

        msg("Wczytano " + loaded.entries.size() + " blokow z " + name + ". Origin: " + fmt(origin) + ".");
        if (loaded.skipped > 0) msg("Pominieto " + loaded.skipped + " blokow, ktorych nie da sie postawic z przedmiotu.");
        msg("Zmien origin przez /schem origin, potem /schem start.");
    }

    private void resetProgress() {
        int n = schematic.entries.size();
        done = new boolean[n];
        attempts = new int[n];
        first = 0;
        doneCount = 0;
        timer = 0;
        warned.clear();
    }

    public void setOrigin(BlockPos pos) {
        origin = pos;
        if (schematic != null) resetProgress();
        msg("Origin: " + fmt(pos));
    }

    public BlockPos origin() {
        return origin;
    }

    public void start() {
        if (schematic == null) {
            msg("Najpierw wczytaj schemat: /schem load <nazwa>");
            return;
        }
        running = true;
        timer = 0;
        msg("Start budowy.");
    }

    public void stop(boolean announce) {
        if (running && announce) msg("Zatrzymano.");
        running = false;
    }

    public void status() {
        if (schematic == null) {
            msg("Brak wczytanego schematu.");
            return;
        }
        msg((running ? "Buduje" : "Zatrzymany") + ": " + loadedName + " " + doneCount + "/" + schematic.entries.size()
            + ", origin " + fmt(origin) + ", zasieg " + range + ", " + blocksPerTick + " blok/tick, delay " + delay + ".");
    }

    private static String fmt(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    public void tick() {
        if (!running || schematic == null || done == null) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.player.networkHandler == null) return;
        if (mc.currentScreen != null) return; // pauza gdy otwarte GUI

        if (timer > 0) {
            timer--;
            return;
        }
        timer = delay;

        List<Schematic.Entry> entries = schematic.entries;
        int n = entries.size();

        while (first < n && done[first]) first++;
        if (first >= n) {
            msg("Budowa zakonczona.");
            running = false;
            return;
        }

        double r2 = range * range;
        Vec3d eye = mc.player.getEyePos();
        PlayerInventory inv = mc.player.getInventory();

        int placed = 0;
        int scanned = 0;

        for (int i = first; i < n && placed < blocksPerTick && scanned < SCAN_BUDGET; i++) {
            if (done[i]) continue;
            scanned++;

            Schematic.Entry e = entries.get(i);
            BlockPos pos = origin.add(e.x(), e.y(), e.z());

            if (eye.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > r2) continue;

            BlockState current = mc.world.getBlockState(pos);
            if (current.isOf(e.block())) {
                done[i] = true;
                doneCount++;
                continue;
            }
            if (!current.isReplaceable()) continue;

            if (attempts[i] >= maxAttempts) {
                done[i] = true;
                continue;
            }

            Item item = e.block().asItem();
            int slot = -1;
            for (int s = 0; s < 36; s++) {
                if (inv.getStack(s).isOf(item)) {
                    slot = s;
                    break;
                }
            }
            if (slot < 0) {
                if (warned.add(e.block())) msg("Brak w ekwipunku: " + e.block().getName().getString());
                continue;
            }

            if (slot >= 9) {
                // przenies do paska (swap z slotem 9), postaw w kolejnej turze
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, slot, SPARE_HOTBAR_SLOT,
                    SlotActionType.SWAP, mc.player);
                timer = 1;
                return;
            }

            BlockHitResult hit = findHit(pos);
            if (hit == null) continue;

            attempts[i]++;
            int prev = inv.getSelectedSlot();
            inv.setSelectedSlot(slot);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
            if (prev != slot) {
                inv.setSelectedSlot(prev);
                mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(prev));
            }
            placed++;
        }
    }

    private BlockHitResult findHit(BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos neighbor = pos.offset(d);
            BlockState state = mc.world.getBlockState(neighbor);

            if (state.isAir() || state.isReplaceable() || !state.getFluidState().isEmpty()) continue;
            if (mc.world.getBlockEntity(neighbor) != null) continue;

            Direction face = d.getOpposite();
            Vec3d hitVec = Vec3d.ofCenter(neighbor).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            return new BlockHitResult(hitVec, face, neighbor, false);
        }
        return null;
    }
}
