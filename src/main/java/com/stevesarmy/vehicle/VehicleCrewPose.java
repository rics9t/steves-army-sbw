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
 *   - the head is clamped to the seat arc and slewed at a human rate with a
 *     dead zone, so tiny target jitter cannot turn into a shake;
 *   - the same point is pushed into the look control, so next tick the AI
 *     agrees with this class instead of fighting it;
 *   - previous rotations are carried over properly, so the client still
 *     interpolates smoothly.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class VehicleCrewPose {

    /** Maximum head traverse per tick, degrees. A person, not a servo. */
    private static final float MAX_HEAD_STEP = 3.0f;
    private static final float MAX_PITCH_STEP = 2.0f;
    /** Ignore corrections smaller than this, the source of the micro shake. */
    private static final float DEAD_ZONE = 0.6f;
    private static final float HEAD_ARC = 70.0f;
    /** Seats that are sealed inside the hull but still allow looking around. */
    private static final float ENCLOSED_HEAD_ARC = 40.0f;
    private static final float PITCH_ARC = 35.0f;
    /**
     * How long a lost target keeps its bearing before the crew goes back to idle
     * scanning. Targets flicker in and out of sight, and flipping the desired yaw
     * every few ticks is what made necks twitch.
     */
    private static final int TARGET_HOLD_TICKS = 40;

    private static final Map<UUID, Pose> POSES = new ConcurrentHashMap<>();
    private static final Map<UUID, WeakReference<SoldierEntity>> MOUNTED = new ConcurrentHashMap<>();

    private VehicleCrewPose() {
    }

    private static final class Pose {
        float headYaw;
        float headYawPrevious;
        float pitch;
        float pitchPrevious;
        float heldYaw;
        float heldPitch;
        int holdTicks;
        final float phase;

        Pose(float yaw, float phase) {
            this.headYaw = yaw;
            this.headYawPrevious = yaw;
            this.phase = phase;
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
        Pose pose = POSES.computeIfAbsent(soldier.getUUID(),
            id -> new Pose(soldier.getYHeadRot(), (id.hashCode() & 0x7fff) * 0.37f));

        float hullYaw = vehicle.getYRot();
        float desiredYaw;
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
        boolean tracking = target != null && target.isAlive()
            && soldier.distanceToSqr(target) < 256.0 * 256.0;

        if (tracking) {
            Vec3 toTarget = target.getEyePosition().subtract(soldier.getEyePosition());
            double flat = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
            desiredYaw = (float) Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
            desiredPitch = (float) -Math.toDegrees(Math.atan2(toTarget.y, Math.max(0.01, flat)));
            pose.heldYaw = desiredYaw;
            pose.heldPitch = desiredPitch;
            pose.holdTicks = TARGET_HOLD_TICKS;
        } else if (pose.holdTicks > 0) {
            // Target just dropped out: keep looking where it was instead of snapping to idle.
            pose.holdTicks--;
            desiredYaw = pose.heldYaw;
            desiredPitch = pose.heldPitch;
        } else {
            // Idle: two slow sines, never repeating in step, so the crew reads
            // as alive without a single abrupt movement.
            double time = (soldier.level().getGameTime() + pose.phase) * 0.0115;
            desiredYaw = hullYaw + (float) (Math.sin(time) * 15.0 + Math.sin(time * 0.41 + 1.7) * 8.0);
            desiredPitch = (float) (Math.sin(time * 0.23) * 4.0);
        }

        float relative = Mth.clamp(Mth.wrapDegrees(desiredYaw - hullYaw), -arc, arc);
        desiredYaw = Mth.wrapDegrees(hullYaw + relative);
        desiredPitch = Mth.clamp(desiredPitch, -PITCH_ARC, PITCH_ARC);
        if (arc <= 0.0f) desiredPitch = 0.0f;

        pose.headYawPrevious = pose.headYaw;
        pose.pitchPrevious = pose.pitch;
        pose.headYaw = approach(pose.headYaw, desiredYaw, MAX_HEAD_STEP);
        pose.pitch = approachLinear(pose.pitch, desiredPitch, MAX_PITCH_STEP);

        // One authority for every rotation field, including the previous tick
        // values the client interpolates from.
        soldier.setYRot(hullYaw);
        soldier.yRotO = hullYaw;
        soldier.setYBodyRot(hullYaw);
        soldier.yBodyRotO = hullYaw;
        soldier.setYHeadRot(pose.headYaw);
        soldier.yHeadRotO = pose.headYawPrevious;
        soldier.setXRot(pose.pitch);
        soldier.xRotO = pose.pitchPrevious;

        // Keep the look control in agreement so it does not fight back next tick.
        Vec3 eye = soldier.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(pose.pitch, pose.headYaw).scale(12.0);
        soldier.getLookControl().setLookAt(eye.x + look.x, eye.y + look.y, eye.z + look.z);
    }

    private static float approach(float current, float desired, float maxStep) {
        float delta = Mth.wrapDegrees(desired - current);
        if (Math.abs(delta) < DEAD_ZONE) return current;
        // Exponential ease plus a hard rate cap: smooth start, no snap.
        float eased = delta * 0.35f;
        float step = Mth.clamp(eased, -maxStep, maxStep);
        return Mth.wrapDegrees(current + step);
    }

    private static float approachLinear(float current, float desired, float maxStep) {
        float delta = desired - current;
        if (Math.abs(delta) < DEAD_ZONE) return current;
        return current + Mth.clamp(delta * 0.35f, -maxStep, maxStep);
    }

    public static void forget(SoldierEntity soldier) {
        if (soldier == null) return;
        POSES.remove(soldier.getUUID());
        MOUNTED.remove(soldier.getUUID());
    }
}
