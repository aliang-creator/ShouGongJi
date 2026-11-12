package com.sg.shougongji.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

import static com.sg.shougongji.ui.UiSupport.button;
import static com.sg.shougongji.ui.UiSupport.label;

final class EffortOverlay extends StackPane {
    private static final double MARGIN = 12;
    private final VBox card = new VBox();
    private final ObservableBooleanValue busy;
    private final Consumer<EffortOverlay> onClose;
    private double grabX;
    private double grabY;

    EffortOverlay(VBox form, ObservableBooleanValue busy, Consumer<EffortOverlay> onClose) {
        this.busy = busy;
        this.onClose = onClose;
        getStyleClass().add("effort-overlay");
        setPickOnBounds(true);
        card.getStyleClass().add("effort-card");
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        card.prefWidthProperty().bind(Bindings.min(570, Bindings.max(0, widthProperty().subtract(2 * MARGIN))));

        HBox title = new HBox(label("云效工时", "panel-title"));
        title.getStyleClass().add("effort-title");
        title.setAlignment(Pos.CENTER_LEFT);
        title.setCursor(Cursor.MOVE);
        title.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                grabX = event.getSceneX() - card.getTranslateX();
                grabY = event.getSceneY() - card.getTranslateY();
                event.consume();
            }
        });
        title.setOnMouseDragged(event -> {
            if (event.isPrimaryButtonDown()) {
                moveCard(event.getSceneX() - grabX, event.getSceneY() - grabY);
                event.consume();
            }
        });

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setFocusTraversable(false);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinHeight(0);
        scroll.maxHeightProperty().bind(Bindings.max(0, heightProperty().subtract(126)));
        VBox.setVgrow(scroll, Priority.SOMETIMES);

        Button close = button("关闭", "secondary");
        close.disableProperty().bind(busy);
        close.setOnAction(event -> close());
        HBox footer = new HBox(close);
        footer.getStyleClass().add("effort-footer");
        footer.setAlignment(Pos.CENTER_RIGHT);
        card.getChildren().addAll(title, scroll, footer);
        getChildren().add(card);
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                close();
                event.consume();
            }
        });
    }

    boolean isBusy() {
        return busy.get();
    }

    void close() {
        if (!isBusy()) {
            onClose.accept(this);
        }
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        moveCard(card.getTranslateX(), card.getTranslateY());
    }

    private void moveCard(double x, double y) {
        double limitX = Math.max(0, (getWidth() - card.getWidth()) / 2 - MARGIN);
        double limitY = Math.max(0, (getHeight() - card.getHeight()) / 2 - MARGIN);
        card.setTranslateX(Math.clamp(x, -limitX, limitX));
        card.setTranslateY(Math.clamp(y, -limitY, limitY));
    }
}
