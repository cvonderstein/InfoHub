package de.cvonderstein.infohub;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Simple text overlay (similar to F3 style, but compact).
 *
 * If the debug HUD (F3) is open, we hide the overlay to avoid overlap.
 */
public final class InfoHubHud {
    private InfoHubHud() {}

    public static void onHudRender(DrawContext drawContext, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        // Avoid overlapping with F3 debug text.
        // (User requested either hiding or moving; we hide here for robustness.)
        if (client.getDebugHud() != null && client.getDebugHud().shouldShowDebugHud()) {
            return;
        }

        TextRenderer tr = client.textRenderer;
        if (tr == null) return;

        InfoHubState s = InfoHubState.INSTANCE;

        int x = 2;
        int y = 2;
        int lineH = tr.fontHeight + 2;

        drawContext.drawTextWithShadow(tr, s.buildHudLine1(), x, y, 0xFFFFFF);
        y += lineH;
        drawContext.drawTextWithShadow(tr, s.buildHudLine2(), x, y, 0xFFFFFF);
        y += lineH;
        drawContext.drawTextWithShadow(tr, s.buildHudLine3(), x, y, 0xFFFFFF);
    }
}
