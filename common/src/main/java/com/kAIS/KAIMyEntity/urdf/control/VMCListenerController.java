package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import com.kAIS.KAIMyEntity.webots.WebotsConfigScreen;
import com.kAIS.KAIMyEntity.webots.WebotsController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class VMCListenerController extends Screen {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int BG_COLOR = 0xB0000000;
    private static final int PANEL_COLOR = 0xFF14161C;
    private static final int TITLE_COLOR = 0xFFFFEA70;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int OK_COLOR = 0xFF55FF55;
    private static final int WARN_COLOR = 0xFFFF5555;
    private static final Component TITLE = Component.literal("RobotListener Control");

    private final Screen parent;
    private final URDFModelOpenGLWithSTL renderer;

    private EditBox ipField;
    private EditBox portField;
    private Button connectButton;
    private Button toggleControlButton;
    private Button openConfigButton;
    private Button closeButton;

    private WebotsController controller;
    private String statusMessage = "";
    private int statusColor = TEXT_COLOR;
    private int refreshTicker = 0;

    public VMCListenerController(Screen parent, URDFModelOpenGLWithSTL renderer) {
        super(TITLE);
        this.parent = parent;
        this.renderer = renderer;
    }

    @Override
    protected void init() {
        super.init();
        this.controller = tryGetController();

        WebotsConfigScreen.Config config = WebotsConfigScreen.Config.getInstance();

        int panelWidth = 240;
        int centerX = this.width / 2;
        int startY = this.height / 2 - 60;

        this.ipField = new EditBox(this.font, centerX - panelWidth / 2, startY, panelWidth, 20,
                Component.literal("IP Address"));
        this.ipField.setValue(config.getLastIp());
        this.ipField.setMaxLength(64);
        addRenderableWidget(this.ipField);

        this.portField = new EditBox(this.font, centerX - panelWidth / 2, startY + 26, panelWidth, 20,
                Component.literal("Port"));
        this.portField.setValue(String.valueOf(config.getLastPort()));
        this.portField.setMaxLength(5);
        addRenderableWidget(this.portField);

        this.connectButton = Button.builder(Component.literal("Connect / Reconnect"), button -> handleConnect())
                .bounds(centerX - panelWidth / 2, startY + 52, panelWidth, 20)
                .build();
        addRenderableWidget(this.connectButton);

        this.toggleControlButton = Button.builder(Component.literal("Enable Robot Control"), button -> handleToggleRobotControl())
                .bounds(centerX - panelWidth / 2, startY + 78, panelWidth, 20)
                .build();
        addRenderableWidget(this.toggleControlButton);

        this.openConfigButton = Button.builder(Component.literal("Open Advanced Settings"), button ->
                Minecraft.getInstance().setScreen(new WebotsConfigScreen(this)))
                .bounds(centerX - panelWidth / 2, startY + 104, panelWidth, 20)
                .build();
        addRenderableWidget(this.openConfigButton);

        this.closeButton = Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(centerX - 50, this.height - 32, 100, 20)
                .build();
        addRenderableWidget(this.closeButton);

        updateToggleButtonText();
        updateButtonStates();
    }

    @Override
    public void tick() {
        super.tick();
        if (ipField != null) {
            ipField.tick();
        }
        if (portField != null) {
            portField.tick();
        }

        if (++refreshTicker >= 20) {
            refreshTicker = 0;
            controller = tryGetController();
            updateToggleButtonText();
            updateButtonStates();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, BG_COLOR);

        int panelX = this.width / 2 - 150;
        int panelY = this.height / 2 - 90;
        int panelW = 300;
        int panelH = 180;
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_COLOR);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, TITLE.getString(), this.width / 2, panelY - 18, TITLE_COLOR);
        graphics.drawString(this.font, "IP Address", panelX + 10, panelY + 8, TEXT_COLOR, false);
        graphics.drawString(this.font, "Port", panelX + 10, panelY + 34, TEXT_COLOR, false);

        if (controller != null) {
            int statusY = panelY + 132;
            boolean connected = controller.isConnected();
            String connection = connected ? "● Connected" : "● Disconnected";
            graphics.drawCenteredString(this.font, connection, this.width / 2, statusY,
                    connected ? OK_COLOR : WARN_COLOR);

            WebotsController.Mode mode = controller.getMode();
            String modeLabel = "Mode: " + (mode == WebotsController.Mode.ROBOTLISTENER ? "RobotListener" : "Webots");
            graphics.drawCenteredString(this.font, modeLabel, this.width / 2, statusY + 12, TEXT_COLOR);

            if (renderer != null && renderer.getRobotModel() != null) {
                graphics.drawCenteredString(this.font, "Robot: " + renderer.getRobotModel().name,
                        this.width / 2, statusY + 24, TEXT_COLOR);
            }

            if (controller.isRobotListenerEnabled()) {
                graphics.drawString(this.font, "Controls:", panelX + 10, statusY - 48, TITLE_COLOR, false);
                graphics.drawString(this.font, "• WASD - Translate", panelX + 14, statusY - 33, TEXT_COLOR, false);
                graphics.drawString(this.font, "• Mouse - Head aim", panelX + 14, statusY - 20, TEXT_COLOR, false);
            }
        } else {
            graphics.drawCenteredString(this.font, "Controller not initialized", this.width / 2,
                    panelY + 132, WARN_COLOR);
        }

        if (!statusMessage.isEmpty()) {
            graphics.drawCenteredString(this.font, statusMessage, this.width / 2, panelY + panelH + 12, statusColor);
        }
    }

    private void handleConnect() {
        String ip = ipField.getValue().trim();
        int port;

        if (ip.isEmpty()) {
            setStatus("IP address cannot be empty", WARN_COLOR);
            return;
        }

        try {
            port = Integer.parseInt(portField.getValue().trim());
            if (port <= 0 || port > 65535) {
                setStatus("Port must be between 1 and 65535", WARN_COLOR);
                return;
            }
        } catch (NumberFormatException e) {
            setStatus("Invalid port", WARN_COLOR);
            return;
        }

        try {
            controller = WebotsController.getInstance(ip, port);
            setStatus("Connected to " + controller.getRobotAddress(), OK_COLOR);
            WebotsConfigScreen.Config.getInstance().update(ip, port);
        } catch (Exception e) {
            LOGGER.error("Failed to connect to {}:{}", ip, port, e);
            setStatus("Connection failed: " + e.getMessage(), WARN_COLOR);
        }

        updateToggleButtonText();
        updateButtonStates();
    }

    private void handleToggleRobotControl() {
        controller = tryGetController();
        if (controller == null) {
            setStatus("WebotsController not available. Connect first.", WARN_COLOR);
            return;
        }

        if (!controller.isConnected()) {
            setStatus("Controller is not connected.", WARN_COLOR);
            return;
        }

        boolean enable = !controller.isRobotListenerEnabled();
        controller.enableRobotListener(enable);
        if (enable) {
            setStatus("Robot control enabled. Use WASD + Mouse.", OK_COLOR);
        } else {
            setStatus("Robot control disabled.", OK_COLOR);
        }

        updateToggleButtonText();
        updateButtonStates();
    }

    private void updateButtonStates() {
        if (toggleControlButton == null) {
            return;
        }

        boolean hasController = controller != null;
        boolean connected = hasController && controller.isConnected();

        toggleControlButton.active = connected;
        if (connectButton != null) {
            connectButton.active = true;
        }
        if (openConfigButton != null) {
            openConfigButton.active = true;
        }
    }

    private void updateToggleButtonText() {
        if (toggleControlButton == null) {
            return;
        }
        if (controller != null && controller.isRobotListenerEnabled()) {
            toggleControlButton.setMessage(Component.literal("Disable Robot Control"));
        } else {
            toggleControlButton.setMessage(Component.literal("Enable Robot Control"));
        }
    }

    private void setStatus(String message, int color) {
        this.statusMessage = message;
        this.statusColor = color;
    }

    private WebotsController tryGetController() {
        try {
            return WebotsController.getInstance();
        } catch (Exception e) {
            return controller;
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
