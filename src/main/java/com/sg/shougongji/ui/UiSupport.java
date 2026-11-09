package com.sg.shougongji.ui;

import javafx.event.ActionEvent;
import javafx.scene.CacheHint;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Window;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;

final class UiSupport {
    static final String STYLES = UiSupport.class.getResource("shougongji.css").toExternalForm();
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter INPUT_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd H:mm[:ss]")
            .withResolverStyle(ResolverStyle.STRICT);

    private UiSupport() {}

    static Scene glassScene(Region content, double width, double height) {
        Region wash = new Region();
        wash.getStyleClass().add("window-wash");
        wash.setMouseTransparent(true);
        wash.setEffect(new GaussianBlur(30));
        wash.setCache(true);
        wash.setCacheHint(CacheHint.QUALITY);
        StackPane root = new StackPane(wash, content);
        root.getStyleClass().add("work-window");
        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(STYLES);
        return scene;
    }

    static SVGPath icon(String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add("line-icon");
        return icon;
    }

    static String clockDisplay(long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    static Dialog<ButtonType> form(Window owner, String title, VBox fields, String action) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        styleDialog(dialog, 480);
        dialog.getDialogPane().getButtonTypes().setAll(
                new ButtonType(action, ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        dialog.getDialogPane().setContent(fields);
        return dialog;
    }

    static void styleDialog(Dialog<?> dialog, double width) {
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().add(STYLES);
        pane.setPrefWidth(width);
    }

    static VBox field(String name, Node control) {
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        Label caption = label(name, "field-label");
        caption.setLabelFor(control);
        return new VBox(5, caption, control);
    }

    static void bindSave(Dialog<ButtonType> dialog, Action save) {
        VBox content = (VBox) dialog.getDialogPane().getContent();
        Label error = label("", "error", "wrap");
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);
        content.getChildren().add(error);
        ButtonType saveType = dialog.getDialogPane().getButtonTypes().getFirst();
        dialog.getDialogPane().lookupButton(saveType).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                save.run();
            } catch (SQLException | IllegalArgumentException failure) {
                error.setText(message(failure));
                error.setVisible(true);
                event.consume();
            }
        });
    }

    static String formatTime(long epochSecond, ZoneId zone) {
        return DATE_TIME.format(Instant.ofEpochSecond(epochSecond).atZone(zone));
    }

    static Instant parseTime(String value, ZoneId zone, LocalDate defaultDate) {
        String input = value.strip();
        if (defaultDate != null && !input.contains(" ")) {
            input = defaultDate + " " + input;
        }
        try {
            LocalDateTime local = LocalDateTime.parse(input, INPUT_TIME);
            List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
            if (offsets.size() != 1) {
                throw new IllegalArgumentException("此时刻正逢工作时区切换，请选择另一个时刻。");
            }
            return local.toInstant(offsets.getFirst());
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("请输入有效时间，例如 2026-11-06 09:30。");
        }
    }

    static void perform(Window owner, Action action) {
        try {
            action.run();
        } catch (SQLException | IllegalArgumentException failure) {
            showError(owner, message(failure));
        }
    }

    private static String message(Exception failure) {
        return failure instanceof SQLException ? "无法访问本地记录库，请稍后重试。" : failure.getMessage();
    }

    static void showError(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle("收工记");
        alert.setHeaderText(null);
        styleDialog(alert, 440);
        alert.showAndWait();
    }

    static Button button(String title, String style) {
        Button button = new Button(title);
        button.getStyleClass().add(style);
        return button;
    }

    static Label label(String title, String... styles) {
        Label label = new Label(title);
        label.getStyleClass().addAll(styles);
        label.setWrapText(label.getStyleClass().contains("wrap"));
        return label;
    }

    static Region spacer() {
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        return space;
    }

    @FunctionalInterface
    interface Action {
        void run() throws SQLException;
    }
}
