package com.stevesarmy.vehicle;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Coarse waypoint pathfinding for AI-driven vehicles.
 *
 * Minecraft's mob navigation assumes a 1-block walker, which is useless for a
 * tank. This runs A* over a sparse grid of drivable cells with a configurable
 * footprint, tolerating one-block step-ups so vehicles climb kerbs and slopes
 * instead of grinding against them.
 */
public final class VehiclePathfinder {

    /** Grid step in blocks. Coarse keeps the search cheap for long hauls. */
    private static final int CELL = 4;
    private static final int MAX_NODES = 1500;
    /** Vehicles can climb this many blocks between adjacent cells. */
    private static final int MAX_STEP_UP = 1;
    private static final int MAX_DROP = 3;
    /** Vertical probe range when snapping a cell to the ground. */
    private static final int GROUND_PROBE = 6;

    private VehiclePathfinder() {
    }

    private record Node(BlockPos pos, double g, double f) {
    }

    /**
     * Returns a queue of waypoints from start to goal, or an empty deque when no
     * route exists. Waypoints are block centres at drivable ground height.
     */
    public static Deque<Vec3> findPath(Level level, BlockPos start, BlockPos goal, double halfWidth) {
        Deque<Vec3> result = new ArrayDeque<>();

        BlockPos from = snapToGround(level, start);
        BlockPos to = snapToGround(level, goal);
        if (from == null || to == null) return result;

        // Straight shot first: most orders do not need a search at all.
        if (isClearLine(level, from, to, halfWidth)) {
            result.add(center(to));
            return result;
        }

        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> best = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));

        open.add(new Node(from, 0, heuristic(from, to)));
        best.put(from, 0.0);

        int expanded = 0;
        BlockPos reached = null;

        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node current = open.poll();
            BlockPos pos = current.pos();
            if (!closed.add(pos)) continue;
            expanded++;

            if (horizontalDistance(pos, to) <= CELL) {
                reached = pos;
                break;
            }

            for (BlockPos next : neighbours(level, pos, halfWidth)) {
                if (closed.contains(next)) continue;
                double stepCost = CELL + Math.abs(next.getY() - pos.getY()) * 1.5;
                double tentative = current.g() + stepCost;
                Double known = best.get(next);
                if (known != null && tentative >= known) continue;
                best.put(next, tentative);
                cameFrom.put(next, pos);
                open.add(new Node(next, tentative, tentative + heuristic(next, to)));
            }
        }

        if (reached == null) return result;

        // Walk parents back, then reverse into travel order.
        List<BlockPos> reversed = new ArrayList<>();
        for (BlockPos p = reached; p != null && !p.equals(from); p = cameFrom.get(p)) {
            reversed.add(p);
        }
        for (int i = reversed.size() - 1; i >= 0; i--) {
            result.add(center(reversed.get(i)));
        }
        // Always finish on the real goal, not the snapped cell.
        result.add(center(to));
        return result;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return horizontalDistance(a, b);
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static List<BlockPos> neighbours(Level level, BlockPos pos, double halfWidth) {
        List<BlockPos> out = new ArrayList<>(8);
        int[][] offsets = {
            {CELL, 0}, {-CELL, 0}, {0, CELL}, {0, -CELL},
            {CELL, CELL}, {CELL, -CELL}, {-CELL, CELL}, {-CELL, -CELL}
        };
        for (int[] o : offsets) {
            BlockPos candidate = snapToGround(level, pos.offset(o[0], 0, o[1]));
            if (candidate == null) continue;
            int rise = candidate.getY() - pos.getY();
            if (rise > MAX_STEP_UP || rise < -MAX_DROP) continue;
            if (!isFootprintClear(level, candidate, halfWidth)) continue;
            out.add(candidate);
        }
        return out;
    }

    /** Drops or raises a cell onto the nearest solid surface. */
    private static BlockPos snapToGround(Level level, BlockPos pos) {
        for (int dy = 0; dy <= GROUND_PROBE; dy++) {
            BlockPos down = pos.below(dy);
            if (isSolid(level, down.below()) && isPassable(level, down)) {
                return down;
            }
        }
        for (int dy = 1; dy <= MAX_STEP_UP + 1; dy++) {
            BlockPos up = pos.above(dy);
            if (isSolid(level, up.below()) && isPassable(level, up)) {
                return up;
            }
        }
        return null;
    }

    private static boolean isSolid(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty()
            && !state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isPassable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return true; // boats
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /** Checks the vehicle's width fits, not just a single column. */
    private static boolean isFootprintClear(Level level, BlockPos pos, double halfWidth) {
        int r = Math.max(0, (int) Math.floor(halfWidth));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos p = pos.offset(dx, 0, dz);
                if (!isPassable(level, p) || !isPassable(level, p.above())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Samples along a straight line to see whether a search is needed at all. */
    private static boolean isClearLine(Level level, BlockPos from, BlockPos to, double halfWidth) {
        double distance = horizontalDistance(from, to);
        if (distance < 1.0) return true;
        int steps = (int) Math.ceil(distance / 2.0);
        if (steps > 64) return false;
        double lastY = from.getY();
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            BlockPos sample = snapToGround(level, new BlockPos(x, from.getY(), z));
            if (sample == null) return false;
            if (Math.abs(sample.getY() - lastY) > MAX_STEP_UP + MAX_DROP) return false;
            if (!isFootprintClear(level, sample, halfWidth)) return false;
            lastY = sample.getY();
        }
        return true;
    }
}
