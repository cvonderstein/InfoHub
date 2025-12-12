package de.cvonderstein.infohub;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InfoHub – a lightweight, client-only HUD + helper overlay.
 *
 * Mod ID: infohub
 * Maven Group / base package: de.cvonderstein
 */
public final class InfoHubClient implements ClientModInitializer {
    public static final String MOD_ID = "infohub";
    public static final Logger LOGGER = LoggerFactory.getLogger("InfoHub");

    public static KeyBinding TOGGLE_SPAWN_MARKERS;

    @Override
    public void onInitializeClient() {
        // Key binding (toggle): spawnable block markers.
        TOGGLE_SPAWN_MARKERS = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.infohub.toggle_spawn_markers",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "category.infohub"
        ));

        // Tick updates (compute all values once per tick / throttled).
        ClientTickEvents.END_CLIENT_TICK.register(InfoHubState.INSTANCE::onClientTick);

        // HUD rendering (text overlay).
        HudRenderCallback.EVENT.register(InfoHubHud::onHudRender);

        // World rendering (spawn marker overlay).
        WorldRenderEvents.AFTER_ENTITIES.register(SpawnMarkerRenderer::onWorldRender);

        // Connection lifecycle (reset state on join/disconnect to avoid stale references).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> InfoHubState.INSTANCE.onJoinWorld(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> InfoHubState.INSTANCE.onLeaveWorld());

        LOGGER.info("InfoHub client initialized");
    }
}
