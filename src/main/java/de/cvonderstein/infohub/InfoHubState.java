package de.cvonderstein.infohub;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Holds computed values and does all per-tick updates.
 *
 * Important (memory safety):
 * - No strong references are kept to players/entities.
 * - Only UUIDs + BlockPos are cached.
 * - All world-specific caches are cleared on disconnect.
 */
public final class InfoHubState {
    public static final InfoHubState INSTANCE = new InfoHubState();

    // ----------------------------
    // Tunables (performance)
    // ----------------------------

    /** Update player/mob counters every N ticks (10 = twice per second). */
    private static final int COUNTER_UPDATE_INTERVAL_TICKS = 10;

    /** Show "new player entered radius" chat message for this radius (in chunks, length-based = chunks*16 blocks). */
    private static final int PLAYER_NOTIFY_RADIUS_CHUNKS = 4; // => 64 blocks

    /** Player counters in radii (chunks, length-based). */
    private static final int[] PLAYER_COUNT_RADII_CHUNKS = {3, 5, 7};

    /** Mob counters in radii (chunks); note: radius=2 is *chunk-border based* per requirement. */
    private static final int[] MOB_COUNT_RADII_CHUNKS = {1, 2, 3, 4};

    /** Spawn-marker scan radius (blocks). */
    private static final int SPAWN_SCAN_RADIUS_BLOCKS = 24;

    /** Spawn-marker scan vertical range (+/- blocks around player Y). */
    private static final int SPAWN_SCAN_VERTICAL_BLOCKS = 12;

    /** Scan interval while spawn markers are enabled. */
    private static final int SPAWN_SCAN_INTERVAL_TICKS = 10;

    /** Hard cap on how many markers we keep to avoid memory/perf issues. */
    private static final int SPAWN_MARKER_MAX = 800;

    // ----------------------------
    // State (read by HUD / renderer)
    // ----------------------------

    private int fps = 0;
    private int rttMs = 0;

    private double speedBps = 0.0; // blocks per second (horizontal)
    private boolean isNight = false;
    private int secondsToTransition = 0;

    private int lightCombined = 0;
    private int lightSky = 0;
    private int lightBlock = 0;

    private final int[] playersInRadius = new int[PLAYER_COUNT_RADII_CHUNKS.length];

    public static final class MobCounts {
        public int hostile = 0;
        public int nonHostile = 0;

        public void reset() {
            hostile = 0;
            nonHostile = 0;
        }
    }

    private final MobCounts[] mobsInRadius = new MobCounts[MOB_COUNT_RADII_CHUNKS.length];

    // Spawn marker overlay
    private boolean spawnMarkersEnabled = false;
    private final List<BlockPos> spawnMarkerPositions = new ArrayList<>();

    // "player entered radius" tracking
    private final Set<UUID> playersWithinNotifyRange = new HashSet<>();
    private boolean notifyInitialized = false;

    // housekeeping
    private int clientTicks = 0;
    private ClientWorld lastWorld = null;

    private InfoHubState() {
        for (int i = 0; i < mobsInRadius.length; i++) {
            mobsInRadius[i] = new MobCounts();
        }
    }

    // ----------------------------
    // Lifecycle
    // ----------------------------

    public void onJoinWorld(MinecraftClient client) {
        // Don't keep world refs across reconnects.
        lastWorld = client.world;
        notifyInitialized = false;
        playersWithinNotifyRange.clear();
        spawnMarkerPositions.clear();
        resetCounters();
    }

    public void onLeaveWorld() {
        lastWorld = null;
        notifyInitialized = false;
        playersWithinNotifyRange.clear();
        spawnMarkerPositions.clear();
        resetCounters();
    }

    private void resetCounters() {
        fps = 0;
        rttMs = 0;
        speedBps = 0.0;
        isNight = false;
        secondsToTransition = 0;
        lightCombined = 0;
        lightSky = 0;
        lightBlock = 0;

        for (int i = 0; i < playersInRadius.length; i++) {
            playersInRadius[i] = 0;
        }
        for (MobCounts mc : mobsInRadius) {
            mc.reset();
        }
    }

    // ----------------------------
    // Tick update
    // ----------------------------

    public void onClientTick(MinecraftClient client) {
        clientTicks++;

        // Hotkey handling
        while (InfoHubClient.TOGGLE_SPAWN_MARKERS.wasPressed()) {
            spawnMarkersEnabled = !spawnMarkersEnabled;
            if (!spawnMarkersEnabled) {
                spawnMarkerPositions.clear();
            }
            if (client.player != null && client.inGameHud != null) {
                client.inGameHud.getChatHud().addMessage(Text.literal(
                        "[InfoHub] Spawn markers: " + (spawnMarkersEnabled ? "ON" : "OFF")
                ));
            }
        }

        final ClientWorld world = client.world;
        final PlayerEntity player = client.player;

        if (world == null || player == null) {
            // Main menu / not in-game
            resetCounters();
            return;
        }

        // World change detection (e.g. dimension switch)
        if (lastWorld != world) {
            lastWorld = world;
            notifyInitialized = false;
            playersWithinNotifyRange.clear();
            spawnMarkerPositions.clear();
        }

        // Fast metrics (every tick, cheap)
        fps = MinecraftClient.getCurrentFps();
        speedBps = computeHorizontalSpeedBps(player);
        updateDayNight(world);
        updateLight(world, player);
        rttMs = computeRttMs(client, player);

        // Throttled counters (players/mobs) – avoid doing this each frame.
        if ((clientTicks % COUNTER_UPDATE_INTERVAL_TICKS) == 0) {
            updatePlayerCountersAndNotifications(world, player, client);
            updateMobCounters(world, player);
        }

        // Spawn marker scan (throttled)
        if (spawnMarkersEnabled && (clientTicks % SPAWN_SCAN_INTERVAL_TICKS) == 0) {
            rescanSpawnMarkers(world, player);
        }
    }

    private static double computeHorizontalSpeedBps(PlayerEntity player) {
        Vec3d v = player.getVelocity();
        // Minecraft tick rate: 20 ticks per second
        return Math.sqrt(v.x * v.x + v.z * v.z) * 20.0;
    }

    private void updateDayNight(ClientWorld world) {
        long t = world.getTimeOfDay() % 24000L;

        // Night is usually considered from 13000..23999.
        isNight = (t >= 13000L);

        long ticksToTransition = isNight ? (24000L - t) : (13000L - t);
        // Convert to seconds (round up so it doesn't flicker to 0 too early)
        secondsToTransition = (int) Math.max(0L, (ticksToTransition + 19L) / 20L);
    }

    private void updateLight(ClientWorld world, PlayerEntity player) {
        // "Block I'm standing on" -> block below feet.
        BlockPos ground = player.getBlockPos().down();

        // Combined light, and split sky/block (useful for spawn reasoning).
        lightCombined = world.getLightLevel(ground);
        lightSky = world.getLightLevel(LightType.SKY, ground);
        lightBlock = world.getLightLevel(LightType.BLOCK, ground);
    }

    private static int computeRttMs(MinecraftClient client, PlayerEntity player) {
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (handler == null) return 0;

        PlayerListEntry entry = handler.getPlayerListEntry(player.getUuid());
        if (entry == null) return 0;

        return Math.max(0, entry.getLatency());
    }

    private void updatePlayerCountersAndNotifications(ClientWorld world, PlayerEntity player, MinecraftClient client) {
        for (int i = 0; i < playersInRadius.length; i++) {
            playersInRadius[i] = 0;
        }

        final double px = player.getX();
        final double pz = player.getZ();

        // Precompute squared radii (length-based chunks => blocks).
        final double r3 = PLAYER_COUNT_RADII_CHUNKS[0] * 16.0;
        final double r5 = PLAYER_COUNT_RADII_CHUNKS[1] * 16.0;
        final double r7 = PLAYER_COUNT_RADII_CHUNKS[2] * 16.0;

        final double r3Sq = r3 * r3;
        final double r5Sq = r5 * r5;
        final double r7Sq = r7 * r7;

        final double notifyR = PLAYER_NOTIFY_RADIUS_CHUNKS * 16.0;
        final double notifyRSq = notifyR * notifyR;

        // Track who is currently within notify radius
        Set<UUID> currentlyInNotifyRange = new HashSet<>();

        for (PlayerEntity other : world.getPlayers()) {
            if (other == player) continue;

            double dx = other.getX() - px;
            double dz = other.getZ() - pz;
            double distSq = dx * dx + dz * dz;

            if (distSq <= r7Sq) {
                // Note: We count inclusively (e.g., if in 3-chunk radius, also counts for 5 and 7).
                if (distSq <= r3Sq) playersInRadius[0]++;
                if (distSq <= r5Sq) playersInRadius[1]++;
                playersInRadius[2]++;

                if (distSq <= notifyRSq) {
                    currentlyInNotifyRange.add(other.getUuid());
                }
            }
        }

        // Notify on "new players entered notify range".
        if (!notifyInitialized) {
            // First update after join/world change -> initialize without messaging.
            playersWithinNotifyRange.clear();
            playersWithinNotifyRange.addAll(currentlyInNotifyRange);
            notifyInitialized = true;
            return;
        }

        for (UUID uuid : currentlyInNotifyRange) {
            if (!playersWithinNotifyRange.contains(uuid)) {
                PlayerEntity newGuy = world.getPlayerByUuid(uuid);
                String name = (newGuy != null && newGuy.getName() != null) ? newGuy.getName().getString() : uuid.toString();
                client.inGameHud.getChatHud().addMessage(Text.literal("[InfoHub] Player nearby (<= " + (PLAYER_NOTIFY_RADIUS_CHUNKS * 16) + "b): " + name));
            }
        }

        playersWithinNotifyRange.clear();
        playersWithinNotifyRange.addAll(currentlyInNotifyRange);
    }

    private void updateMobCounters(ClientWorld world, PlayerEntity player) {
        for (MobCounts mc : mobsInRadius) mc.reset();

        final double px = player.getX();
        final double pz = player.getZ();

        // Largest radius is 4 chunks => 64 blocks.
        final double maxR = 4 * 16.0;

        // Get mobs in a bounding box first, then apply distance / chunk rules.
        int bottomY = world.getBottomY();
        int topY = world.getTopY();

        Box box = new Box(px - maxR, bottomY, pz - maxR, px + maxR, topY, pz + maxR);

        List<MobEntity> mobs = world.getEntitiesByClass(MobEntity.class, box, (e) -> true);

        // For the special "2 chunk radius" (chunk-border based)
        ChunkPos playerChunk = new ChunkPos(player.getBlockPos());

        // Squared radii for the length-based ones
        final double r1Sq = (1 * 16.0) * (1 * 16.0);
        final double r3Sq = (3 * 16.0) * (3 * 16.0);
        final double r4Sq = (4 * 16.0) * (4 * 16.0);

        for (MobEntity mob : mobs) {
            if (mob.isRemoved()) continue;

            boolean hostile = mob instanceof HostileEntity;

            // Length-based radii (1/3/4)
            double dx = mob.getX() - px;
            double dz = mob.getZ() - pz;
            double distSq = dx * dx + dz * dz;

            if (distSq <= r1Sq) {
                inc(mobsInRadius[0], hostile);
            }

            // Special: 2 chunks is chunk-border based.
            if (isWithinChunkRadius(mob, playerChunk, 2)) {
                inc(mobsInRadius[1], hostile);
            }

            if (distSq <= r3Sq) {
                inc(mobsInRadius[2], hostile);
            }
            if (distSq <= r4Sq) {
                inc(mobsInRadius[3], hostile);
            }
        }
    }

    private static boolean isWithinChunkRadius(Entity e, ChunkPos center, int chunkRadius) {
        ChunkPos mobChunk = new ChunkPos(e.getBlockPos());
        return Math.abs(mobChunk.x - center.x) <= chunkRadius
                && Math.abs(mobChunk.z - center.z) <= chunkRadius;
    }

    private static void inc(MobCounts mc, boolean hostile) {
        if (hostile) mc.hostile++;
        else mc.nonHostile++;
    }

    private void rescanSpawnMarkers(ClientWorld world, PlayerEntity player) {
        spawnMarkerPositions.clear();

        final int cx = player.getBlockPos().getX();
        final int cy = player.getBlockPos().getY();
        final int cz = player.getBlockPos().getZ();

        final int minY = Math.max(world.getBottomY() + 1, cy - SPAWN_SCAN_VERTICAL_BLOCKS);
        final int maxY = Math.min(world.getTopY() - 2, cy + SPAWN_SCAN_VERTICAL_BLOCKS);

        BlockPos.Mutable ground = new BlockPos.Mutable();
        BlockPos.Mutable spawn = new BlockPos.Mutable();
        BlockPos.Mutable head = new BlockPos.Mutable();

        // Scan for hostile-mob spawnable spots (simple heuristic):
        // - solid ground block
        // - 2 blocks of empty collision above (for typical 2-block tall mobs)
        // - block light level at spawn position == 0
        for (int dx = -SPAWN_SCAN_RADIUS_BLOCKS; dx <= SPAWN_SCAN_RADIUS_BLOCKS; dx++) {
            for (int dz = -SPAWN_SCAN_RADIUS_BLOCKS; dz <= SPAWN_SCAN_RADIUS_BLOCKS; dz++) {
                for (int y = minY; y <= maxY; y++) {
                    ground.set(cx + dx, y, cz + dz);
                    spawn.set(ground.getX(), ground.getY() + 1, ground.getZ());
                    head.set(ground.getX(), ground.getY() + 2, ground.getZ());

                    if (isHostileSpawnableSpot(world, ground, spawn, head)) {
                        // Store spawn position (the air block where the mob's feet would be).
                        spawnMarkerPositions.add(spawn.toImmutable());
                        if (spawnMarkerPositions.size() >= SPAWN_MARKER_MAX) {
                            return; // hard cap
                        }
                    }
                }
            }
        }
    }

    private static boolean isHostileSpawnableSpot(ClientWorld world, BlockPos ground, BlockPos spawn, BlockPos head) {
        BlockState groundState = world.getBlockState(ground);
        if (groundState.isAir()) return false;

        // Solid top surface
        if (!groundState.isSolidBlock(world, ground)) return false;

        // Space for body/head (collision must be empty, and no fluids)
        BlockState spawnState = world.getBlockState(spawn);
        if (!spawnState.getCollisionShape(world, spawn).isEmpty()) return false;
        if (!world.getFluidState(spawn).isEmpty()) return false;

        BlockState headState = world.getBlockState(head);
        if (!headState.getCollisionShape(world, head).isEmpty()) return false;
        if (!world.getFluidState(head).isEmpty()) return false;

        // Hostile-mob relevant light rule: "block light" must be 0.
        // (This matches current modern spawning behavior for most hostile mobs.)
        int blockLight = world.getLightLevel(LightType.BLOCK, spawn);
        return blockLight == 0;
    }

    // ----------------------------
    // Getters (used by HUD / renderer)
    // ----------------------------

    public int getFps() {
        return fps;
    }

    public int getRttMs() {
        return rttMs;
    }

    public double getSpeedBps() {
        return speedBps;
    }

    public boolean isNight() {
        return isNight;
    }

    public int getSecondsToTransition() {
        return secondsToTransition;
    }

    public int getLightCombined() {
        return lightCombined;
    }

    public int getLightSky() {
        return lightSky;
    }

    public int getLightBlock() {
        return lightBlock;
    }

    public int getPlayersInRadiusIndex(int index) {
        if (index < 0 || index >= playersInRadius.length) return 0;
        return playersInRadius[index];
    }

    public MobCounts getMobsInRadiusIndex(int index) {
        if (index < 0 || index >= mobsInRadius.length) return mobsInRadius[0];
        return mobsInRadius[index];
    }

    public boolean isSpawnMarkersEnabled() {
        return spawnMarkersEnabled;
    }

    /**
     * Render-only access – do not modify this list.
     */
    public List<BlockPos> getSpawnMarkerPositions() {
        return spawnMarkerPositions;
    }

    public String buildHudLine1() {
        String tnTd = isNight ? "TD" : "TN";
        return String.format(Locale.ROOT,
                "FPS:%d RTT:%dms V:%.2f %s:%ds L:%d(%d/%d)",
                fps,
                rttMs,
                speedBps,
                tnTd,
                secondsToTransition,
                lightCombined,
                lightSky,
                lightBlock
        );
    }

    public String buildHudLine2() {
        return String.format(Locale.ROOT,
                "P3/5/7:%d/%d/%d  SM:%s",
                playersInRadius[0],
                playersInRadius[1],
                playersInRadius[2],
                spawnMarkersEnabled ? "ON" : "OFF"
        );
    }

    public String buildHudLine3() {
        MobCounts m1 = mobsInRadius[0];
        MobCounts m2 = mobsInRadius[1];
        MobCounts m3 = mobsInRadius[2];
        MobCounts m4 = mobsInRadius[3];

        // M(H/N) means: hostile / non-hostile
        return String.format(Locale.ROOT,
                "M(H/N) 1:%d/%d 2:%d/%d 3:%d/%d 4:%d/%d",
                m1.hostile, m1.nonHostile,
                m2.hostile, m2.nonHostile,
                m3.hostile, m3.nonHostile,
                m4.hostile, m4.nonHostile
        );
    }
}
