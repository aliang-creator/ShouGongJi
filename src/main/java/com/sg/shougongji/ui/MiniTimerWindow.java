package com.sg.shougongji.ui;

import javafx.animation.AnimationTimer;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import static com.sg.shougongji.ui.UiSupport.button;
import static com.sg.shougongji.ui.UiSupport.icon;
import static com.sg.shougongji.ui.UiSupport.label;
import static com.sg.shougongji.ui.UiSupport.spacer;

final class MiniTimerWindow extends Stage {
    private static final double WIDTH = 220;
    private static final double HEIGHT = 60;
    private static final double EDGE_GAP = 16;

    private double dragX;
    private double dragY;
    private double nextX;
    private double nextY;
    private boolean positionPending;
    private final AnimationTimer move = new AnimationTimer() {
        @Override
        public void handle(long now) {
            stop();
            applyPosition();
        }
    };

    MiniTimerWindow(Stage timer, ObservableValue<String> title, ObservableValue<String> time) {
        super(StageStyle.TRANSPARENT);
        setTitle("计时浮窗");
        setResizable(false);
        setAlwaysOnTop(true);

        Label running = label("计时中", "mini-timer-state");
        Region dot = new Region();
        dot.getStyleClass().add("status-dot");
        running.setGraphic(dot);
        running.setGraphicTextGap(5);
        running.setMinWidth(Region.USE_PREF_SIZE);
        Label elapsed = label("", "mini-timer-elapsed");
        elapsed.textProperty().bind(time);
        elapsed.setMinWidth(0);
        HBox heading = new HBox(8, running, spacer(), elapsed);
        heading.setAlignment(Pos.CENTER_LEFT);

        Label task = label("", "mini-timer-task");
        task.textProperty().bind(title);
        task.setMinWidth(0);
        Tooltip fullTitle = new Tooltip();
        fullTitle.textProperty().bind(title);
        task.setTooltip(fullTitle);
        VBox readout = new VBox(2, heading, task);
        readout.setAlignment(Pos.CENTER_LEFT);
        readout.setMinWidth(0);
        readout.setMaxHeight(Double.MAX_VALUE);
        readout.getStyleClass().add("mini-timer-readout");
        HBox.setHgrow(readout, Priority.ALWAYS);
        readout.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                dragX = event.getScreenX() - getX();
                dragY = event.getScreenY() - getY();
            }
        });
        readout.setOnMouseDragged(event -> {
            if (event.isPrimaryButtonDown()) {
                nextX = event.getScreenX() - dragX;
                nextY = event.getScreenY() - dragY;
                positionPending = true;
                move.start();
            }
        });
        readout.setOnMouseReleased(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                move.stop();
                applyPosition();
            }
        });

        Button open = button("", "mini-timer-open");
        open.setGraphic(icon("M3 10 L10 3 M4 3 H10 V9"));
        open.setAccessibleText("打开计时主窗");
        open.setTooltip(new Tooltip("打开计时主窗"));
        open.setOnAction(event -> {
            timer.setIconified(false);
            timer.toFront();
            timer.requestFocus();
        });

        HBox content = new HBox(9, readout, open);
        content.setAlignment(Pos.CENTER_LEFT);
        content.getStyleClass().add("mini-timer");
        Scene scene = new Scene(content, WIDTH, HEIGHT, Color.TRANSPARENT);
        scene.getStylesheets().add(UiSupport.STYLES);
        setScene(scene);
        setOnShowing(event -> placeIfNeeded(timer));
        setOnHidden(event -> {
            move.stop();
            positionPending = false;
        });
    }

    private void applyPosition() {
        if (!positionPending) {
            return;
        }
        positionPending = false;
        if (nextX != getX()) {
            setX(nextX);
        }
        if (nextY != getY()) {
            setY(nextY);
        }
    }

    private void placeIfNeeded(Stage timer) {
        if (Double.isFinite(getX()) && Double.isFinite(getY())
                && !Screen.getScreensForRectangle(getX(), getY(), WIDTH, HEIGHT).isEmpty()) {
            return;
        }
        ObservableList<Screen> screens = Screen.getScreensForRectangle(timer.getX() + timer.getWidth() / 2,
                timer.getY() + timer.getHeight() / 2, 1, 1);
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.getFirst();
        Rectangle2D area = screen.getVisualBounds();
        setX(area.getMaxX() - WIDTH - EDGE_GAP);
        setY(area.getMinY() + EDGE_GAP);
    }
}
