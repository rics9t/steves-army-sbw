package com.stevesarmy.compat.sbw;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reflective facade over an SBW vehicle. All driving, seating, health and
 * weapon-station access for soldiers goes through here so no other class in
 * the mod references SBW types.
 */
public final class SbwVehicles {

    private SbwVehicles() {
    }

    // -- capacity and seating -------------------------------------------------

    public static int maxPassengers(Entity vehicle) {
        return SbwReflection.asInt(
            SbwReflection.get(vehicle, "maxPassengers", "getMaxPassengers"), 0);
    }

    public static int freeSeats(Entity vehicle) {
        if (!SbwCompat.isVehicle(vehicle)) return 0;
        return Math.max(0, maxPassengers(vehicle) - vehicle.getPassengers().size());
    }

    public static boolean hasFreeSeat(Entity vehicle) {
        return freeSeats(vehicle) > 0;
    }

    public static int seatIndexOf(Entity vehicle, Entity passenger) {
        return SbwReflection.asInt(
            SbwReflection.call(vehicle, new String[]{"getSeatIndex"}, passenger), -1);
    }

    public static Entity passengerAt(Entity vehicle, int index) {
        Object o = SbwReflection.call(vehicle, new String[]{"getNthEntity"}, index);
        return o instanceof Entity e ? e : null;
    }

    /** Seat 0 is the driver seat in every native SBW vehicle. */
    public static Entity driver(Entity vehicle) {
        return passengerAt(vehicle, 0);
    }

    public static boolean isDriver(Entity vehicle, Entity passenger) {
        return seatIndexOf(vehicle, passenger) == 0;
    }

    private static Object seatInfo(Entity vehicle, int seatIndex) {
        return SbwReflection.call(vehicle, new String[]{"getSeat"}, seatIndex);
    }

    /**
     * Open seats permit handheld weapons. SBW models this as SeatInfo.banHand,
     * so a soldier may shoot its own gun only when the seat does not ban hands.
     */
    public static boolean seatAllowsHandheld(Entity vehicle, int seatIndex) {
        Object seat = seatInfo(vehicle, seatIndex);
        if (seat == null) return false;
        Object ban = SbwReflection.get(seat, "banHand", "getBanHand", "isBanHand");
        return !SbwReflection.asBool(ban, false);
    }

    /** Weapon station names available to the given seat, possibly empty. */
    @SuppressWarnings("unchecked")
    public static List<String> seatWeapons(Entity vehicle, int seatIndex) {
        Object seat = seatInfo(vehicle, seatIndex);
        if (seat == null) return List.of();
        Object weapons = SbwReflection.call(seat, new String[]{"weapons", "getWeapons"});
        if (weapons instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof String s) out.add(s);
            }
            return out;
        }
        return List.of();
    }

    public static boolean seatHasWeapon(Entity vehicle, int seatIndex) {
        return !seatWeapons(vehicle, seatIndex).isEmpty();
    }

    public static String getGunName(Entity vehicle, int seatIndex) {
        Object res = SbwReflection.call(vehicle, new String[]{"getGunName"}, seatIndex);
        return res instanceof String s ? s : null;
    }

    // -- health & fuel --------------------------------------------------------

    public static float health(Entity vehicle) {
        return SbwReflection.asFloat(SbwReflection.get(vehicle, "health", "getHealth"), 0f);
    }

    public static float maxHealth(Entity vehicle) {
        return SbwReflection.asFloat(SbwReflection.get(vehicle, "maxHealth", "getMaxHealth"), 0f);
    }

    /** A vehicle is wrecked once health is non-positive or it left the world. */
    public static boolean isWrecked(Entity vehicle) {
        if (vehicle == null || vehicle.isRemoved()) return true;
        if (!SbwCompat.isVehicle(vehicle)) return false;
        return health(vehicle) <= 0f;
    }

    /** Ensure the vehicle has sufficient energy to drive and operate systems. */
    public static void ensureFuel(Entity vehicle) {
        if (!SbwCompat.isVehicle(vehicle)) return;
        Object energyObj = SbwReflection.get(vehicle, "energy", "getEnergy");
        int energy = SbwReflection.asInt(energyObj, 0);
        Object maxEnergyObj = SbwReflection.get(vehicle, "maxEnergy", "getMaxEnergy");
        int maxEnergy = SbwReflection.asInt(maxEnergyObj, 100000);
        if (energy < 2000 && maxEnergy > 0) {
            SbwReflection.set(vehicle, Math.max(maxEnergy, 50000), "energy");
        }
    }

    public static float power(Entity vehicle) {
        return SbwReflection.asFloat(SbwReflection.get(vehicle, "power", "getPower"), 0.0f);
    }

    public static void setPower(Entity vehicle, float p) {
        SbwReflection.set(vehicle, p, "power");
    }

    // -- driving inputs -------------------------------------------------------

    public static void input(Entity vehicle, String name, boolean down) {
        SbwReflection.set(vehicle, down, name);
    }

    public static void forward(Entity vehicle, boolean down) {
        input(vehicle, "forwardInputDown", down);
    }

    public static void back(Entity vehicle, boolean down) {
        input(vehicle, "backInputDown", down);
    }

    public static void left(Entity vehicle, boolean down) {
        input(vehicle, "leftInputDown", down);
    }

    public static void right(Entity vehicle, boolean down) {
        input(vehicle, "rightInputDown", down);
    }

    public static void up(Entity vehicle, boolean down) {
        input(vehicle, "upInputDown", down);
    }

    public static void down(Entity vehicle, boolean down) {
        input(vehicle, "downInputDown", down);
    }

    public static void fire(Entity vehicle, boolean down) {
        input(vehicle, "fireInputDown", down);
    }

    public static void sprint(Entity vehicle, boolean down) {
        input(vehicle, "sprintInputDown", down);
    }

    public static void updateInputs(Entity vehicle, boolean forward, boolean back, boolean left, boolean right, boolean sprint) {
        if (!SbwCompat.isVehicle(vehicle)) return;
        short keys = 0;
        if (left) keys |= 0b00000001;
        if (right) keys |= 0b00000010;
        if (forward) keys |= 0b00000100;
        if (back) keys |= 0b00001000;
        if (sprint) keys |= 256;
        SbwReflection.call(vehicle, new String[]{"processInput"}, keys);

        forward(vehicle, forward);
        back(vehicle, back);
        left(vehicle, left);
        right(vehicle, right);
        sprint(vehicle, sprint);
    }

    public static void clearInputs(Entity vehicle) {
        updateInputs(vehicle, false, false, false, false, false);
        up(vehicle, false);
        down(vehicle, false);
        fire(vehicle, false);
    }

    // -- weapon stations ------------------------------------------------------

    public static int ammoCount(Entity vehicle, int seatIndex) {
        return SbwReflection.asInt(
            SbwReflection.call(vehicle, new String[]{"getAmmoCount"}, seatIndex), 0);
    }

    public static int weaponHeat(Entity vehicle, int seatIndex) {
        return SbwReflection.asInt(
            SbwReflection.call(vehicle, new String[]{"getWeaponHeat"}, seatIndex), 0);
    }

    public static Vec3 shootPos(Entity vehicle, int seatIndex) {
        Object res = SbwReflection.call(vehicle, new String[]{"getShootPos"}, seatIndex, 1.0f);
        return res instanceof Vec3 v ? v : vehicle.position();
    }

    public static void aimWeapon(Entity vehicle, int seatIndex, Vec3 aimVec) {
        if (!SbwCompat.isVehicle(vehicle) || aimVec == null) return;
        Class<?> utilsClass = SbwReflection.cls("com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleWeaponUtils");
        if (utilsClass == null) return;
        int turretIdx = SbwReflection.asInt(SbwReflection.get(vehicle, "turretControllerIndex", "getTurretControllerIndex"), 0);
        int weaponStationIdx = SbwReflection.asInt(SbwReflection.get(vehicle, "passengerWeaponStationControllerIndex", "getPassengerWeaponStationControllerIndex"), 1);

        if (seatIndex == turretIdx) {
            SbwReflection.callStatic(utilsClass, new String[]{"turretAutoAimFromVector"}, vehicle, aimVec);
        } else if (seatIndex == weaponStationIdx) {
            SbwReflection.callStatic(utilsClass, new String[]{"passengerWeaponAutoAimFormVector"}, vehicle, aimVec);
        }
    }

    public static boolean shoot(Entity vehicle, LivingEntity gunner, UUID targetId, Vec3 targetPos) {
        if (vehicle == null || gunner == null || targetPos == null) return false;
        int seatIndex = seatIndexOf(vehicle, gunner);
        String weaponName = getGunName(vehicle, seatIndex);
        if (weaponName == null) return false;

        Method m = SbwReflection.method(vehicle.getClass(), 3, "vehicleShoot");
        if (m != null) {
            try {
                Class<?> p1 = m.getParameterTypes()[1];
                if (p1 == String.class) {
                    m.invoke(vehicle, gunner, weaponName, targetPos);
                    return true;
                } else if (p1 == UUID.class) {
                    m.invoke(vehicle, gunner, targetId, targetPos);
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        Method m2 = SbwReflection.method(vehicle.getClass(), 2, "vehicleShoot");
        if (m2 != null) {
            try {
                m2.invoke(vehicle, gunner, targetPos);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public static void destroy(Entity vehicle) {
        SbwReflection.call(vehicle, new String[]{"destroy"});
    }

    // -- classification, for movement tuning ----------------------------------

    public enum Kind { GROUND, TRACKED, BOAT, HELICOPTER, AIRCRAFT, STATIC, UNKNOWN }

    public static Kind kind(Entity vehicle) {
        if (!SbwCompat.isVehicle(vehicle)) return Kind.UNKNOWN;
        String n = vehicle.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (n.contains("artillery") || n.contains("mortar") || n.contains("tower")
            || n.contains("annihilator") || n.contains("laser")) {
            return Kind.STATIC;
        }
        if (n.contains("boat") || n.contains("ship")) return Kind.BOAT;
        if (n.contains("heli") || n.contains("ah6") || n.contains("mi28")
            || n.contains("ghast") || n.contains("drone")) {
            return Kind.HELICOPTER;
        }
        if (n.contains("a10") || n.contains("ju87") || n.contains("j16")
            || n.contains("ac130") || n.contains("kirov") || n.contains("plane")) {
            return Kind.AIRCRAFT;
        }
        if (n.contains("tank") || n.contains("m1a2") || n.contains("t90")
            || n.contains("ztz") || n.contains("bmp") || n.contains("yx100")
            || n.contains("prism") || n.contains("type63")) {
            return Kind.TRACKED;
        }
        return Kind.GROUND;
    }

    public static boolean isAirborne(Entity vehicle) {
        Kind k = kind(vehicle);
        return k == Kind.HELICOPTER || k == Kind.AIRCRAFT;
    }

    public static boolean canDrive(Entity vehicle) {
        Kind k = kind(vehicle);
        return k != Kind.STATIC && k != Kind.UNKNOWN;
    }
}