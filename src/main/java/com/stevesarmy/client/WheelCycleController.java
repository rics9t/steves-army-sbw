package com.stevesarmy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Multi-page state for the command wheel. The middle-mouse ping session cycles through
 * ping orders, vehicle transport, and vehicle control pages. Hold mode uses the configured
 * cycle button; click mode uses right-click. Releasing the wheel confirms the current page.
 */
public final class WheelCycleController {
    public enum Page {
        PING_ORDERS,
        VEHICLE,
        VEHICLE_CONTROL
    }

    public enum CycleButton {
        LEFT(GLFW.GLFW_MOUSE_BUTTON_LEFT, "wheel.steves_army.switch_hint_lmb"),
        RIGHT(GLFW.GLFW_MOUSE_BUTTON_RIGHT, "wheel.steves_army.switch_hint_rmb");

        public final int glfwButton;
        public final String hintKey;

        CycleButton(int glfwButton, String hintKey) {
            this.glfwButton = glfwButton;
            this.hintKey = hintKey;
        }
    }

    private static boolean wasCycleButtonDown = false;
    private static Page page = Page.PING_ORDERS;

    private WheelCycleController() {}

    /** True while any hold-to-open wheel session has the mouse released. */
    public static boolean isAnyHoldWheelActive() {
        return PingWheelHandler.isWheelActive()
            || FormationWheelHandler.isWheelActive()
            || FireTeamWheelHandler.isWheelActive();
    }

    /** Called when the ping wheel session opens; the default page is the ping orders wheel. */
    public static void onSessionActivated() {
        page = Page.PING_ORDERS;
        int button = WheelInteractionController.isClickMode() ? GLFW.GLFW_MOUSE_BUTTON_RIGHT
            : StevesArmyClientConfig.VEHICLE_WHEEL_CYCLE_BUTTON.get().glfwButton;
        wasCycleButtonDown = GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), button) == GLFW.GLFW_PRESS;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        int cycleButton = WheelInteractionController.isClickMode() ? GLFW.GLFW_MOUSE_BUTTON_RIGHT
            : StevesArmyClientConfig.VEHICLE_WHEEL_CYCLE_BUTTON.get().glfwButton;
        boolean cycleButtonDown = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), cycleButton) == GLFW.GLFW_PRESS;
        boolean pressed = cycleButtonDown && !wasCycleButtonDown;
        wasCycleButtonDown = cycleButtonDown;

        if (!PingWheelHandler.isWheelActive()) {
            page = Page.PING_ORDERS;
            return;
        }
        if (!StevesArmyClientConfig.ENABLE_VEHICLE_WHEEL.get()) {
            return;
        }
        if (pressed && !WheelInteractionController.isClickMode()) {
            cyclePage();
        }
    }

    public static void onRightClick() {
        if (PingWheelHandler.isWheelActive() && StevesArmyClientConfig.ENABLE_VEHICLE_WHEEL.get()) {
            cyclePage();
        }
    }

    private static void cyclePage() {
        page = switch (page) {
            case PING_ORDERS -> Page.VEHICLE;
            case VEHICLE -> Page.VEHICLE_CONTROL;
            case VEHICLE_CONTROL -> Page.PING_ORDERS;
        };
    }

    public static boolean isVehiclePage() {
        return page == Page.VEHICLE || page == Page.VEHICLE_CONTROL;
    }

    public static boolean isVehicleControlPage() {
        return page == Page.VEHICLE_CONTROL;
    }

    public static Page getPage() {
        return page;
    }

    /**
     * Page title above the wheel plus the cycling hint, drawn by whichever page is
     * currently displayed so players can discover the vehicle page.
     */
    public static void drawPageHeader(GuiGraphics guiGraphics, Component pageTitle) {
        Minecraft mc = Minecraft.getInstance();
        int centerX = mc.getWindow().getGuiScaledWidth() / 2;
        int headerY = mc.getWindow().getGuiScaledHeight() / 2 - 96;
        guiGraphics.drawCenteredString(mc.font, pageTitle, centerX, headerY, 0xFFFFFFFF);
        if (StevesArmyClientConfig.ENABLE_VEHICLE_WHEEL.get()) {
            guiGraphics.drawCenteredString(mc.font,
                Component.translatable(WheelInteractionController.isClickMode() ? CycleButton.RIGHT.hintKey
                    : StevesArmyClientConfig.VEHICLE_WHEEL_CYCLE_BUTTON.get().hintKey),
                centerX, headerY + mc.font.lineHeight, 0xFF888888);
        }
    }
}
