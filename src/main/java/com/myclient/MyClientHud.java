package com.myclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-only HUD: keystrokes, CPS, FPS, coordinates, armor durability, potion effects.
 * No mixins, so it touches as little of Minecraft's internals as possible.
 * Right Shift toggles the whole HUD (rebindable in Controls).
 */
public class MyClientHud implements ClientModInitializer {
    public static final String MOD_ID = "myclient";

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;
    private static final int RED = 0xFFFF5555;
    private static final int KEY_UP_BG = 0x80000000;
    private static final int KEY_DOWN_BG = 0xB0FFFFFF;

    private static KeyMapping toggleKey;
    private static boolean hudVisible = true;

    private final ArrayDeque<Long> leftClicks = new ArrayDeque<>();
    private final ArrayDeque<Long> rightClicks = new ArrayDeque<>();
    private boolean leftWasDown;
    private boolean rightWasDown;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.myclient.toggle_hud",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_RSHIFT,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                hudVisible = !hudVisible;
            }
        });

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "hud"),
                this::extract);
    }

    // ---------------------------------------------------------------- render

    private void extract(GuiGraphicsExtractor g, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }

        long now = System.currentTimeMillis();
        trackClicks(mc, now); // keep counting even while the HUD is hidden

        if (!hudVisible) {
            return;
        }

        drawInfo(g, mc, now);
        drawKeystrokes(g, mc, now);
        drawRightSide(g, mc);
    }

    // ------------------------------------------------------------------ CPS

    private void trackClicks(Minecraft mc, long now) {
        boolean left = mc.options.keyAttack.isDown();
        if (left && !leftWasDown) {
            leftClicks.addLast(now);
        }
        leftWasDown = left;

        boolean right = mc.options.keyUse.isDown();
        if (right && !rightWasDown) {
            rightClicks.addLast(now);
        }
        rightWasDown = right;

        purge(leftClicks, now);
        purge(rightClicks, now);
    }

    private static void purge(ArrayDeque<Long> clicks, long now) {
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000L) {
            clicks.pollFirst();
        }
    }

    // ------------------------------------------------------------ top-left info

    private void drawInfo(GuiGraphicsExtractor g, Minecraft mc, long now) {
        List<String> lines = new ArrayList<>();
        lines.add("FPS: " + mc.getFps());
        lines.add("CPS: " + leftClicks.size() + " | " + rightClicks.size());
        lines.add(String.format("XYZ: %.1f / %.1f / %.1f",
                mc.player.getX(), mc.player.getY(), mc.player.getZ()));

        int x = 6;
        int y = 6;
        for (String line : lines) {
            int w = mc.font.width(line);
            g.fill(x - 2, y - 2, x + w + 2, y + 10, 0x60000000);
            g.text(mc.font, line, x, y, WHITE, true);
            y += 13;
        }
    }

    // --------------------------------------------------------------- keystrokes

    private void drawKeystrokes(GuiGraphicsExtractor g, Minecraft mc, long now) {
        int size = 22;
        int gap = 3;
        int step = size + gap;
        int height = mc.getWindow().getGuiScaledHeight();

        int x = 8;
        int y = height - (step * 4) - 6;

        // W
        keyBox(g, mc, "W", x + step, y, size, size, mc.options.keyUp.isDown());
        // A S D
        keyBox(g, mc, "A", x, y + step, size, size, mc.options.keyLeft.isDown());
        keyBox(g, mc, "S", x + step, y + step, size, size, mc.options.keyDown.isDown());
        keyBox(g, mc, "D", x + step * 2, y + step, size, size, mc.options.keyRight.isDown());
        // Mouse buttons with CPS
        int mouseW = (step * 3 - gap) / 2;
        keyBox(g, mc, "L " + leftClicks.size(), x, y + step * 2, mouseW, size,
                mc.options.keyAttack.isDown());
        keyBox(g, mc, "R " + rightClicks.size(), x + mouseW + gap, y + step * 2, mouseW, size,
                mc.options.keyUse.isDown());
        // Space bar
        keyBox(g, mc, "-----", x, y + step * 3, step * 3 - gap, 14, mc.options.keyJump.isDown());
    }

    private void keyBox(GuiGraphicsExtractor g, Minecraft mc, String label,
                        int x, int y, int w, int h, boolean down) {
        g.fill(x, y, x + w, y + h, down ? KEY_DOWN_BG : KEY_UP_BG);
        int textW = mc.font.width(label);
        int tx = x + (w - textW) / 2;
        int ty = y + (h - 8) / 2;
        g.text(mc.font, label, tx, ty, down ? BLACK : WHITE, false);
    }

    // ----------------------------------------------------- top-right: armor + potions

    private void drawRightSide(GuiGraphicsExtractor g, Minecraft mc) {
        int screenW = mc.getWindow().getGuiScaledWidth();
        int y = 6;

        EquipmentSlot[] slots = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        };
        String[] names = {"Helmet", "Chest", "Legs", "Boots"};

        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = mc.player.getItemBySlot(slots[i]);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                continue;
            }
            int max = stack.getMaxDamage();
            int left = max - stack.getDamageValue();
            boolean low = left < max * 0.15;
            y = rightLine(g, mc, screenW, y, names[i] + ": " + left + "/" + max, low ? RED : WHITE);
        }

        y += 4;

        for (MobEffectInstance effect : mc.player.getActiveEffects()) {
            String name = effect.getEffect().value().getDisplayName().getString();
            int level = effect.getAmplifier() + 1;
            String time = effect.isInfiniteDuration() ? "inf" : formatTime(effect.getDuration() / 20);
            boolean expiring = !effect.isInfiniteDuration() && effect.getDuration() <= 200;
            y = rightLine(g, mc, screenW, y, name + " " + level + " (" + time + ")",
                    expiring ? RED : WHITE);
        }
    }

    private int rightLine(GuiGraphicsExtractor g, Minecraft mc, int screenW, int y,
                          String text, int color) {
        int w = mc.font.width(text);
        int x = screenW - w - 6;
        g.fill(x - 2, y - 2, x + w + 2, y + 10, 0x60000000);
        g.text(mc.font, text, x, y, color, true);
        return y + 13;
    }

    private static String formatTime(int totalSeconds) {
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
