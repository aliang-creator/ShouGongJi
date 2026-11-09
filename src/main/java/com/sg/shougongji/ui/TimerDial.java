package com.sg.shougongji.ui;

import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;

final class TimerDial extends StackPane {
    private static final double DIAMETER = 212;
    private static final double CENTER = DIAMETER / 2;
    private static final double TRACK_RADIUS = CENTER - 9;
    private final Arc sweep = new Arc(CENTER, CENTER, TRACK_RADIUS, TRACK_RADIUS, 90, 0);
    private final Circle hand = new Circle(3);

    TimerDial() {
        setMinSize(DIAMETER, DIAMETER);
        setPrefSize(DIAMETER, DIAMETER);
        setMaxSize(DIAMETER, DIAMETER);
        getStyleClass().add("timer-dial");
        Region face = new Region();
        face.getStyleClass().add("dial-face");
        Circle track = new Circle(CENTER, CENTER, TRACK_RADIUS);
        track.getStyleClass().add("dial-track");
        Path minorTicks = new Path();
        minorTicks.getStyleClass().add("dial-minor-ticks");
        Path majorTicks = new Path();
        majorTicks.getStyleClass().add("dial-major-ticks");
        for (int second = 0; second < 60; second++) {
            double angle = second * Math.PI / 30;
            double outer = TRACK_RADIUS - 5;
            boolean major = second % 5 == 0;
            double inner = outer - (major ? 7 : 3);
            Path marks = major ? majorTicks : minorTicks;
            marks.getElements().addAll(
                    new MoveTo(CENTER + Math.sin(angle) * inner, CENTER - Math.cos(angle) * inner),
                    new LineTo(CENTER + Math.sin(angle) * outer, CENTER - Math.cos(angle) * outer));
        }
        sweep.setType(ArcType.OPEN);
        sweep.getStyleClass().add("dial-sweep");
        hand.getStyleClass().add("dial-hand");
        Pane scale = new Pane(track, minorTicks, majorTicks, sweep, hand);
        scale.setMouseTransparent(true);
        getChildren().addAll(face, scale);
        update(0, false);
    }

    void update(long seconds, boolean running) {
        int second = (int) (seconds % 60);
        sweep.setVisible(running && second > 0);
        sweep.setLength(-6.0 * second);
        hand.setVisible(running);
        double angle = second * Math.PI / 30;
        hand.setCenterX(CENTER + Math.sin(angle) * TRACK_RADIUS);
        hand.setCenterY(CENTER - Math.cos(angle) * TRACK_RADIUS);
    }
}
