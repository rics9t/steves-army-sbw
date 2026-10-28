package com.stevesarmy.vehicle;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.sbw.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the head and body rotation of every soldier riding an SBW vehicle.
 *
 * Why this class exists: three systems used to write a mounted soldier's
 * rotation in the same tick. SBW's seat code forces the body yaw to the hull,
 * {@code Mob#tickHeadTurn} then clamps the head against the body and rotates
 * the body back, and the soldier's own look control snapped the head onto its
 * target. Each of them undid the previous one, which is the violent head shake
 * seen inside tanks.
 *
 * The fix is a single authority that runs at the end of the server tick, after
 * all AI has had its say:
 *   - body yaw is pinned to the hull, so nothing can rotate it;
 *   - the head is clamped to the seat arc, uses brief saccades for large gaze
 *     changes, and tracks small target motion smoothly;
 *   - the same point is pushed into the look control, so next tick the AI
 *     agrees with this class instead of fighting it;
 *   - previous rotations are carried over properly, so the client still
 *     interpolates smoothly.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class VehicleCrewPose {

    private static final float SACCADE_THRESHOLD = 7.0f;
    private static final float HEAD_ARC = 70.0f;
    /** Seats that are sealed inside the hull but still allow looking around. */
    private static final float ENCLOSED_HEAD_ARC = 40.0f;
    private static final float PITCH_ARC = 35.0f;
    private static final int TARGET_HOLD_TICKS = 40;

    private static final Map<UUID, Pose> POSES = new ConcurrentHashMap<>();
    private static final Map<UUID, WeakReference<SoldierEntity>> MOUNTED = new ConcurrentHashMap<>();

    private VehicleCrewPose() {
    }

    private static final class Pose {
        float relYaw;
        float pitch;
        float fromYaw;
        float toYaw;
        float fromPitch;
        float toPitch;
        float heldRelYaw;
        float heldPitch;
        float glanceYaw;
        float glancePitch;
        float drift;
        float driftVelocity;
        float lastHead = Float.NaN;
        float lastPitch = Float.NaN;
        int saccadeTick;
        int saccadeLength;
        int fixationTicks;
        int reactionTicks;
        int holdTicks;
        int side;
        long nextGlanceAt;
        LivingEntity lastTarget;

        Pose(float relYaw, float pitch) {
            this.relYaw = relYaw;
            this.pitch = pitch;
        }
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!(event.getEntityMounting() instanceof SoldierEntity soldier)) return;
        if (soldier.level().isClientSide) return;
        UUID id = soldier.getUUID();
        if (event.isMounting() && SbwCompat.isVehicle(event.getEntityBeingMounted())) {
            MOUNTED.put(id, new WeakReference<>(soldier));
        } else {
            MOUNTED.remove(id);
            POSES.remove(id);
        }
    }

    /**
     * Mount events are not replayed when a chunk reloads with soldiers already seated, so a
     * hull driven by a player lost its crew pose authority and the vanilla head turn fought
     * SBW again. The soldier calls this every tick while seated; it is a cheap map check.
     */
    public static void ensureTracked(SoldierEntity soldier) {
        if (soldier == null || soldier.level().isClientSide) return;
        UUID id = soldier.getUUID();
        if (!MOUNTED.containsKey(id)) MOUNTED.put(id, new WeakReference<>(soldier));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!SbwCompat.hasVehicles()) return;

        Set<UUID> handled = new HashSet<>();

        // Crew of every AI driven hull, including gunners and riders.
        for (VehicleDriver driver : VehicleDriverRegistry.all()) {
            Entity vehicle = driver.vehicle();
            if (vehicle == null || vehicle.isRemoved()) continue;
            for (Entity passenger : vehicle.getPassengers()) {
                if (passenger instanceof SoldierEntity soldier && handled.add(soldier.getUUID())) {
                    apply(soldier, vehicle);
                }
            }
        }

        // Soldiers riding player driven or captured hulls.
        Iterator<Map.Entry<UUID, WeakReference<SoldierEntity>>> it = MOUNTED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, WeakReference<SoldierEntity>> entry = it.next();
            SoldierEntity soldier = entry.getValue().get();
            if (soldier == null || !soldier.isAlive() || soldier.level().isClientSide) {
                it.remove();
                POSES.remove(entry.getKey());
                continue;
            }
            Entity ride = soldier.getVehicle();
            if (!SbwCompat.isVehicle(ride) || SbwVehicles.isDrone(ride)) {
                it.remove();
                POSES.remove(entry.getKey());
                continue;
            }
            if (handled.add(soldier.getUUID())) apply(soldier, ride);
        }

        POSES.keySet().removeIf(id -> !handled.contains(id));
    }

    private static void apply(SoldierEntity soldier, Entity vehicle) {
        float hullYaw = vehicle.getYRot();
        Pose pose = POSES.computeIfAbsent(soldier.getUUID(), id ->
            new Pose(Mth.wrapDegrees(soldier.getYHeadRot() - hullYaw), soldier.getXRot()));
        float desiredRelYaw;
        float desiredPitch;

        // Seat rules: a seat that cannot rotate the head keeps it dead ahead, a seat sealed
        // inside the hull gets a short arc so the head does not clip through armour.
        float arc = HEAD_ARC;
        int seat = SbwVehicles.seatIndexOf(vehicle, soldier);
        if (seat >= 0) {
            if (!SbwVehicles.seatAllowsHeadRotation(vehicle, seat)) {
                arc = 0.0f;
            } else if (!SbwVehicles.seatAllowsHandheld(vehicle, seat)) {
                arc = ENCLOSED_HEAD_ARC;
            }
        }

        LivingEntity target = soldier.getTarget();
        boolean tracking = arc > 0.0f && target != null && target.isAlive()
            && soldier.distanceToSqr(target) < 96.0 * 96.0;

        if (tracking) {
            Vec3 toTarget = target.getEyePosition().subtract(soldier.getEyePosition());
            double flat = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
            float desiredWorldYaw = (float) Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
            desiredRelYaw = Mth.wrapDegrees(desiredWorldYaw - hullYaw);
            desiredPitch = (float) -Math.toDegrees(Math.atan2(toTarget.y, Math.max(0.01, flat)));

            // Keep the target on the same side of the seat when it crosses behind
            // the soldier. Clamping a wrapped world angle directly swaps arc edges.
            if (Math.abs(desiredRelYaw) <= arc) {
                pose.side = 0;
            } else {
                int targetSide = desiredRelYaw >= 0.0f ? 1 : -1;
                if (pose.side == 0 || (Math.abs(desiredRelYaw) < 150.0f
                    && targetSide != pose.side)) {
                    pose.side = targetSide;
                }
                desiredRelYaw = pose.side * arc;
            }

            pose.holdTicks = TARGET_HOLD_TICKS;
            if (pose.lastTarget != target) {
                pose.lastTarget = target;
                pose.reactionTicks = 2 + soldier.getRandom().nextInt(5);
                pose.saccadeLength = 0;
                pose.fixationTicks = 0;
            }
            pose.heldRelYaw = desiredRelYaw;
            pose.heldPitch = desiredPitch;
        } else if (pose.holdTicks > 0) {
            // Target just dropped out: keep looking where it was instead of snapping to idle.
            pose.holdTicks--;
            desiredRelYaw = pose.heldRelYaw;
            desiredPitch = pose.heldPitch;
        } else {
            pose.lastTarget = null;
            pose.side = 0;
            if (soldier.level().getGameTime() >= pose.nextGlanceAt) {
                var random = soldier.getRandom();
                float spread = arc * (random.nextFloat() < 0.25f ? 0.95f : 0.45f);
                pose.glanceYaw = Mth.clamp((float) (random.nextGaussian() * spread), -arc, arc);
                pose.glancePitch = (float) random.nextGaussian() * 5.0f;
                pose.nextGlanceAt = soldier.level().getGameTime()
                    + 25 + random.nextInt(random.nextFloat() < 0.3f ? 160 : 70);
            }
            desiredRelYaw = pose.glanceYaw;
            desiredPitch = pose.glancePitch;
        }

        desiredRelYaw = Mth.clamp(desiredRelYaw, -arc, arc);
        desiredPitch = Mth.clamp(desiredPitch, -PITCH_ARC, PITCH_ARC);
        if (arc <= 0.0f) desiredPitch = 0.0f;

        if (pose.reactionTicks > 0) {
            pose.reactionTicks--;
        } else if (pose.saccadeLength > 0) {
            pose.saccadeTick++;
            float t = Math.min(1.0f, pose.saccadeTick / (float) pose.saccadeLength);
            float eased = t * t * t * (10.0f + t * (-15.0f + 6.0f * t));
            pose.relYaw = pose.fromYaw + (pose.toYaw - pose.fromYaw) * eased;
            pose.pitch = pose.fromPitch + (pose.toPitch - pose.fromPitch) * eased;
            if (t >= 1.0f) {
                pose.saccadeLength = 0;
                pose.fixationTicks = 4 + soldier.getRandom().nextInt(10);
            }
        } else {
            float yawError = Mth.wrapDegrees(desiredRelYaw - pose.relYaw);
            float pitchError = desiredPitch - pose.pitch;
            if (pose.fixationTicks > 0) pose.fixationTicks--;
            if ((Math.abs(yawError) > SACCADE_THRESHOLD || Math.abs(pitchError) > 8.0f)
                && pose.fixationTicks <= 0) {
                float amplitude = Math.max(Math.abs(yawError), Math.abs(pitchError));
                pose.fromYaw = pose.relYaw;
                pose.fromPitch = pose.pitch;
                pose.toYaw = pose.relYaw + yawError * (0.86f + soldier.getRandom().nextFloat() * 0.12f);
                pose.toPitch = pose.pitch + pitchError * 0.9f;
                pose.saccadeLength = Mth.clamp(2 + Math.round(amplitude / 14.0f), 2, 7);
                pose.saccadeTick = 0;
            } else if (tracking) {
                pose.relYaw += Mth.clamp(yawError * 0.5f, -2.5f, 2.5f);
                pose.pitch += Mth.clamp(pitchError * 0.5f, -2.0f, 2.0f);
            }
        }

        var random = soldier.getRandom();
        pose.driftVelocity = pose.driftVelocity * 0.85f + (random.nextFloat() - 0.5f) * 0.12f;
        pose.drift = Mth.clamp(pose.drift + pose.driftVelocity, -0.8f, 0.8f);
        pose.relYaw = Mth.clamp(pose.relYaw, -arc, arc);
        pose.pitch = Mth.clamp(pose.pitch, -PITCH_ARC, PITCH_ARC);
        float headYaw = Mth.wrapDegrees(hullYaw + pose.relYaw + (arc > 0.0f ? pose.drift : 0.0f));
        writePose(soldier, vehicle, headYaw, pose.pitch, pose);
    }

    /** Writes the body, head, pitch and previous-tick rotations from one place. */
    private static void writePose(SoldierEntity soldier, Entity vehicle,
                                  float headYaw, float pitch, Pose pose) {
        float hullYaw = vehicle.getYRot();
        float previousHead = Float.isNaN(pose.lastHead) ? headYaw : pose.lastHead;
        float previousPitch = Float.isNaN(pose.lastPitch) ? pitch : pose.lastPitch;
        soldier.setYRot(hullYaw);
        soldier.yRotO = vehicle.yRotO;
        soldier.setYBodyRot(hullYaw);
        soldier.yBodyRotO = vehicle.yRotO;
        soldier.setYHeadRot(headYaw);
        soldier.yHeadRotO = previousHead;
        soldier.setXRot(pitch);
        soldier.xRotO = previousPitch;
        pose.lastHead = headYaw;
        pose.lastPitch = pitch;

        Vec3 eye = soldier.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(pitch, headYaw).scale(12.0);
        soldier.getLookControl().setLookAt(eye.x + look.x, eye.y + look.y, eye.z + look.z);
    }

    public static void forget(SoldierEntity soldier) {
        if (soldier == null) return;
        POSES.remove(soldier.getUUID());
        MOUNTED.remove(soldier.getUUID());
    }
}
