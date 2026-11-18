package com.sg.shougongji.ui;

import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.model.Yunxiao.ProtocolException;
import com.sg.shougongji.service.QuickEffortService;
import com.sg.shougongji.service.QuickEffortService.Row;
import com.sg.shougongji.service.YunxiaoService;
import com.sg.shougongji.service.YunxiaoService.ManualEffortRequired;
import com.sg.shougongji.service.YunxiaoService.UncertainWrite;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.sg.shougongji.ui.UiSupport.background;
import static com.sg.shougongji.ui.UiSupport.button;
import static com.sg.shougongji.ui.UiSupport.label;
import static com.sg.shougongji.ui.UiSupport.spacer;
import static com.sg.shougongji.ui.UiSupport.styleDialog;

final class QuickEffortDialog {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE);
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final LocalDate day;
    private final QuickEffortService service;
    private final YunxiaoService connection;
    private final YunxiaoDialogs yunxiao;
    private final Runnable onSuccess;
    private final Map<String, RowEditor> rows = new LinkedHashMap<>();
    private final SimpleBooleanProperty busy = new SimpleBooleanProperty();
    private final PauseTransition autosave = new PauseTransition(Duration.millis(450));
    private final VBox rowList = new VBox(7);
    private final Button yesterday = button("沿用昨天", "secondary");
    private final MenuButton recent = new MenuButton("最近任务");
    private final Button search = button("搜索云效任务…", "subtle");
    private final Button copy = button("复制清单", "subtle");
    private final Label total = label("", "quick-effort-total");
    private final Label summary = label("", "muted", "wrap");
    private final Label storageStatus = label("", "error", "wrap");
    private final Button submit;
    private List<Binding> yesterdayTasks = List.of();
    private List<Binding> recentTasks = List.of();
    private boolean loaded;
    private boolean dirty;

    QuickEffortDialog(Window owner, LocalDate day, QuickEffortService service, YunxiaoService connection,
                      YunxiaoDialogs yunxiao, Runnable onSuccess) {
        this.day = day;
        this.service = service;
        this.connection = connection;
        this.yunxiao = yunxiao;
        this.onSuccess = onSuccess;
        dialog.initOwner(owner);
        dialog.setTitle("快速登记工时");
        dialog.setResizable(true);
        styleDialog(dialog, 700);
        dialog.getDialogPane().getStyleClass().add("quick-effort");
        dialog.getDialogPane().setHeader(new HBox(label("快速登记工时")));
        dialog.getDialogPane().getHeader().getStyleClass().add("header-panel");
        dialog.setOnShown(event -> {
            if (window() instanceof Stage stage) {
                stage.setMinWidth(580);
                stage.setMinHeight(340);
            }
        });

        ButtonType submitType = new ButtonType("登记到云效", ButtonBar.ButtonData.OK_DONE);
        ButtonType closeType = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(closeType, submitType);
        submit = (Button) dialog.getDialogPane().lookupButton(submitType);
        submit.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            submitBatch();
        });
        dialog.getDialogPane().lookupButton(closeType).disableProperty().bind(busy);
        dialog.setOnCloseRequest(event -> {
            if (busy.get() || (!saveDraft() && !confirmCloseWithoutDraft())) {
                event.consume();
            }
        });
        dialog.setOnHidden(event -> autosave.stop());
        autosave.setOnFinished(event -> saveDraft());
        dialog.getDialogPane().setContent(content());
        refreshActions();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    void show() {
        if (isShowing()) {
            window().requestFocus();
            return;
        }
        dialog.show();
        load();
    }

    private VBox content() {
        yesterday.setOnAction(event -> useYesterday());
        search.setOnAction(event -> yunxiao.chooseEffortTask(window(), connection, (project, item) -> {
            setBusy(true);
            background(() -> service.addTask(connection, project, item), binding -> {
                if (recentTasks.stream().noneMatch(task -> task.childId().equals(binding.childId()))) {
                    List<Binding> updated = new ArrayList<>(recentTasks);
                    updated.addFirst(binding);
                    recentTasks = List.copyOf(updated);
                }
                setBusy(false);
                addAndFocus(binding);
            }, failure -> {
                setBusy(false);
                storageStatus.setText(YunxiaoDialogs.errorText(failure));
            });
        }));
        HBox actions = new HBox(8, label(day.format(DATE), "section-title"), spacer(), yesterday, recent, search);
        actions.setAlignment(Pos.CENTER_LEFT);
        ScrollPane scroll = new ScrollPane(rowList);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(245);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        rowList.disableProperty().bind(busy);
        storageStatus.managedProperty().bind(storageStatus.textProperty().isNotEmpty());
        summary.managedProperty().bind(summary.textProperty().isNotEmpty());
        copy.visibleProperty().bind(copy.disableProperty().not());
        copy.managedProperty().bind(copy.visibleProperty());
        copy.setOnAction(event -> copyReceipt());
        HBox totals = new HBox(8, total, spacer(), copy);
        totals.setAlignment(Pos.CENTER_LEFT);
        VBox footer = new VBox(3, totals, summary, storageStatus);
        footer.getStyleClass().add("quick-effort-footer");
        return new VBox(12, actions, scroll, footer);
    }

    private Window window() {
        return dialog.getDialogPane().getScene().getWindow();
    }

    private void load() {
        loaded = false;
        dirty = false;
        setBusy(true);
        summary.setText("正在读取工时…");
        background(() -> service.load(connection, day), form -> {
            rows.clear();
            rowList.getChildren().clear();
            yesterdayTasks = form.yesterday();
            recentTasks = form.recent();
            form.rows().forEach(this::add);
            loaded = true;
            if (rows.isEmpty()) {
                showEmpty();
            }
            refreshRecent();
            setBusy(false);
            Platform.runLater(this::focusFirstAction);
        }, failure -> {
            setBusy(false);
            summary.setText(YunxiaoDialogs.errorText(failure));
            Button retry = button("重新读取", "secondary");
            retry.setOnAction(event -> load());
            rowList.getChildren().setAll(retry);
        });
    }

    private void showEmpty() {
        String hint = yesterdayTasks.isEmpty() ? "添加今天做过的云效任务。" : "沿用昨天的事项，或添加今天做过的任务。";
        rowList.getChildren().setAll(label(hint, "muted", "quick-effort-empty"));
    }

    private void focusFirstAction() {
        RowEditor first = rows.values().stream().filter(row -> !row.submitted).findFirst().orElse(null);
        if (first != null) {
            first.focusHours();
        } else if (!yesterday.isDisabled()) {
            yesterday.requestFocus();
        } else if (!recent.isDisabled()) {
            recent.requestFocus();
        } else {
            search.requestFocus();
        }
    }

    private void useYesterday() {
        RowEditor first = null;
        for (Binding binding : yesterdayTasks) {
            if (!rows.containsKey(binding.childId())) {
                RowEditor row = add(new Row(binding, "", "", false));
                if (first == null) {
                    first = row;
                }
            }
        }
        if (first != null) {
            refreshRecent();
            changed();
            first.focusHours();
        }
    }

    private void addAndFocus(Binding binding) {
        RowEditor row = rows.get(binding.childId());
        if (row == null) {
            row = add(new Row(binding, "", "", false));
            refreshRecent();
            changed();
        }
        row.focusHours();
    }

    private RowEditor add(Row initial) {
        if (rows.isEmpty()) {
            rowList.getChildren().clear();
        }
        RowEditor row = new RowEditor(initial);
        rows.put(row.binding.childId(), row);
        rowList.getChildren().add(row.view);
        return row;
    }

    private void changed() {
        dirty = true;
        autosave.playFromStart();
        refreshActions();
    }

    private boolean saveDraft() {
        autosave.stop();
        if (!dirty) {
            return true;
        }
        try {
            service.saveDraft(connection, day, rows.values().stream().map(RowEditor::snapshot).toList());
            dirty = false;
            storageStatus.setText("");
            return true;
        } catch (SQLException | IllegalArgumentException failure) {
            storageStatus.setText("草稿保存失败。请检查本地记录库后重试。");
            return false;
        }
    }

    private void setBusy(boolean value) {
        busy.set(value);
        refreshActions();
    }

    private boolean confirmCloseWithoutDraft() {
        ButtonType close = new ButtonType("关闭窗口", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "最近的修改未保存到草稿，关闭后可能需要重新填写。", close, ButtonType.CANCEL);
        alert.initOwner(window());
        alert.setHeaderText("工时草稿未保存");
        styleDialog(alert, 440);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == close;
    }

    private void refreshRecent() {
        List<MenuItem> choices = new ArrayList<>();
        for (Binding binding : recentTasks) {
            if (!rows.containsKey(binding.childId())) {
                MenuItem choice = new MenuItem(binding.label());
                choice.setOnAction(event -> addAndFocus(binding));
                choices.add(choice);
            }
        }
        recent.getItems().setAll(choices);
    }

    private void refreshActions() {
        boolean unavailable = busy.get() || !loaded;
        recent.setDisable(unavailable || recent.getItems().isEmpty());
        yesterday.setDisable(unavailable || yesterdayTasks.stream().allMatch(binding -> rows.containsKey(binding.childId())));
        search.setDisable(unavailable);
        submit.setDisable(unavailable || rows.values().stream().noneMatch(RowEditor::readyToSubmit));
        copy.setDisable(unavailable || rows.values().stream().noneMatch(row -> row.submitted));
        if (unavailable) {
            return;
        }
        BigDecimal hours = BigDecimal.ZERO;
        int submitted = 0;
        int filled = 0;
        int failed = 0;
        for (RowEditor row : rows.values()) {
            if (row.hours.getText().isBlank()) {
                continue;
            }
            try {
                hours = hours.add(EffortDuration.parseHours(row.hours.getText()));
                filled++;
            } catch (IllegalArgumentException invalid) {
                // 未填完整的时长在提交时逐行提示。
            }
            if (row.submitted) {
                submitted++;
            }
            if (row.failure != null) {
                failed++;
            }
        }
        total.setText("合计 " + EffortDuration.display(hours) + " · " + filled + " 项");
        if (filled > 0 && filled == submitted && failed == 0) {
            summary.setText("本次 " + submitted + " 项已登记到云效。");
        } else if (failed > 0) {
            summary.setText("已登记 " + submitted + " 项，" + failed + " 项待处理。");
        } else {
            summary.setText(submitted > 0 ? "已登记 " + submitted + " 项。" : "");
        }
        submit.setText(failed > 0 ? "重试未登记工时" : "登记到云效");
    }

    private void copyReceipt() {
        List<Row> submitted = rows.values().stream().filter(row -> row.submitted).map(RowEditor::snapshot).toList();
        BigDecimal hours = BigDecimal.ZERO;
        StringBuilder entries = new StringBuilder();
        for (Row row : submitted) {
            BigDecimal amount = EffortDuration.parseHours(row.hours());
            hours = hours.add(amount);
            entries.append("\n").append(row.binding().label()).append(" · ").append(EffortDuration.display(amount));
            if (!row.note().isBlank()) {
                entries.append(" · ").append(row.note().strip());
            }
        }
        ClipboardContent content = new ClipboardContent();
        content.putString("工时清单 · " + day + " · " + EffortDuration.display(hours) + entries);
        if (Clipboard.getSystemClipboard().setContent(content)) {
            summary.setText("已复制 " + submitted.size() + " 项登记工时。");
        }
    }

    private void submitBatch() {
        List<Attempt> attempts = new ArrayList<>();
        for (RowEditor row : rows.values()) {
            if (row.readyToSubmit()) {
                Attempt attempt = validate(row, false, false);
                if (attempt == null) {
                    return;
                }
                attempts.add(attempt);
            }
        }
        submit(attempts);
    }

    private Attempt validate(RowEditor row, boolean retryConfirmed, boolean verify) {
        Row snapshot = row.snapshot();
        try {
            return new Attempt(row, snapshot, EffortDuration.parseHours(snapshot.hours()), retryConfirmed, verify);
        } catch (IllegalArgumentException invalid) {
            row.message(invalid.getMessage(), true);
            row.focusHours();
            return null;
        }
    }

    private void submit(List<Attempt> attempts) {
        if (attempts.isEmpty() || !saveDraft()) {
            return;
        }
        setBusy(true);
        summary.setText("正在登记 " + attempts.size() + " 项工时…");
        background(() -> upload(attempts), results -> {
            boolean changed = false;
            for (Result result : results) {
                result.attempt().editor().finish(result.failure(), result.attempt().verify());
                changed |= result.failure() == null;
            }
            setBusy(false);
            if (changed) {
                onSuccess.run();
            }
        }, failure -> {
            setBusy(false);
            summary.setText(YunxiaoDialogs.errorText(failure));
        });
    }

    private List<Result> upload(List<Attempt> attempts) throws InterruptedException {
        List<Result> results = new ArrayList<>();
        for (Attempt attempt : attempts) {
            try {
                service.submit(connection, day, attempt.row(), attempt.hours(), attempt.retryConfirmed(), attempt.verify());
                results.add(new Result(attempt, null));
            } catch (IOException | SQLException | IllegalArgumentException | ProtocolException failure) {
                results.add(new Result(attempt, failure));
            }
        }
        return results;
    }

    private void retry(RowEditor row) {
        boolean verify = row.failure instanceof ManualEffortRequired;
        boolean uncertain = row.failure instanceof UncertainWrite;
        if (uncertain) {
            ButtonType retry = new ButtonType("确认未登记，重试", ButtonBar.ButtonData.OK_DONE);
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "请先在云效核对「" + row.binding.label() + "」在 " + day + " 的工时。", retry, ButtonType.CANCEL);
            alert.setHeaderText("核对上一次登记");
            alert.initOwner(window());
            styleDialog(alert, 480);
            if (alert.showAndWait().orElse(ButtonType.CANCEL) != retry) {
                return;
            }
        }
        Attempt attempt = validate(row, uncertain, verify);
        if (attempt != null) {
            submit(List.of(attempt));
        }
    }

    private void focusOtherDuration(RowEditor current, KeyEvent event) {
        if (event.getCode() != KeyCode.TAB || event.isAltDown() || event.isControlDown() || event.isMetaDown()) {
            return;
        }
        List<RowEditor> order = new ArrayList<>(rows.values());
        int destination = order.indexOf(current) + (event.isShiftDown() ? -1 : 1);
        if (destination >= 0 && destination < order.size()) {
            order.get(destination).focusHours();
            event.consume();
        }
    }

    private void remove(RowEditor row) {
        rows.remove(row.binding.childId());
        rowList.getChildren().remove(row.view);
        if (rows.isEmpty()) {
            showEmpty();
        }
        refreshRecent();
        changed();
    }

    private final class RowEditor {
        private final Binding binding;
        private final TextField hours;
        private final TextField note;
        private final Button remove = button("移除", "subtle");
        private final Label feedback = label("", "muted", "wrap");
        private final HBox recovery = new HBox(5);
        private final VBox view;
        private boolean submitted;
        private Throwable failure;

        RowEditor(Row row) {
            binding = row.binding();
            submitted = row.submitted();
            Label name = label(binding.label(), "section-title", "wrap");
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            hours = new TextField(row.hours());
            hours.setPromptText("如 45m");
            hours.setPrefColumnCount(7);
            hours.setAccessibleText(binding.label() + " 的工时");
            hours.getStyleClass().add("quick-effort-duration");
            hours.addEventFilter(KeyEvent.KEY_PRESSED, event -> focusOtherDuration(this, event));
            note = new TextField(row.note());
            note.setPromptText("备注（选填）");
            note.setPrefColumnCount(10);
            note.setAccessibleText(binding.label() + " 的备注");
            note.getStyleClass().add("quick-effort-note");
            remove.setAccessibleText("移出本次登记 " + binding.label());
            remove.setTooltip(new Tooltip("移出本次登记"));
            remove.setOnAction(event -> remove(this));
            HBox inputs = new HBox(8, name, hours, note, remove);
            inputs.setAlignment(Pos.CENTER_LEFT);
            feedback.managedProperty().bind(feedback.textProperty().isNotEmpty());
            recovery.managedProperty().bind(recovery.visibleProperty());
            view = new VBox(4, inputs, feedback, recovery);
            view.getStyleClass().add("quick-effort-row");
            showResult();
            hours.textProperty().addListener((observable, oldValue, newValue) -> edit());
            note.textProperty().addListener((observable, oldValue, newValue) -> edit());
        }

        boolean readyToSubmit() {
            return !submitted && !hours.getText().isBlank() && !(failure instanceof ManualEffortRequired)
                    && !(failure instanceof UncertainWrite);
        }

        Row snapshot() {
            return new Row(binding, hours.getText(), note.getText(), submitted);
        }

        void focusHours() {
            hours.requestFocus();
            hours.selectAll();
        }

        void edit() {
            submitted = false;
            if (!(failure instanceof ManualEffortRequired) && !(failure instanceof UncertainWrite)) {
                failure = null;
            }
            showResult();
            changed();
        }

        void finish(Throwable failure, boolean verifying) {
            if (verifying && failure != null) {
                message(YunxiaoDialogs.errorText(failure), true);
                return;
            }
            submitted = failure == null;
            this.failure = failure;
            showResult();
        }

        void showResult() {
            remove.setVisible(!submitted);
            feedback.getStyleClass().remove("quick-effort-saved");
            recovery.getChildren().clear();
            message(submitted ? "已登记" : "", false);
            if (submitted) {
                feedback.getStyleClass().add("quick-effort-saved");
            }
            if (failure != null) {
                message(YunxiaoDialogs.errorText(failure), true);
                addRecovery();
            }
            recovery.setVisible(!recovery.getChildren().isEmpty());
        }

        void message(String text, boolean error) {
            feedback.setText(text);
            feedback.getStyleClass().remove("error");
            if (error) {
                feedback.getStyleClass().add("error");
            }
        }

        private void addRecovery() {
            boolean manual = failure instanceof ManualEffortRequired;
            boolean uncertain = failure instanceof UncertainWrite;
            if ((manual || uncertain) && !connection.workItemPage(binding.childId()).isBlank()) {
                Button open = button("打开云效任务", "subtle");
                open.setOnAction(event -> yunxiao.openWorkItem(connection, binding.childId()));
                recovery.getChildren().add(open);
            }
            if (failure instanceof ManualEffortRequired required && !required.newRecordDescription().isBlank()) {
                Button copy = button("复制登记说明", "subtle");
                copy.setOnAction(event -> {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(required.newRecordDescription());
                    if (Clipboard.getSystemClipboard().setContent(content)) {
                        message("已复制登记说明。请在云效登记后核对。", false);
                    }
                });
                recovery.getChildren().add(copy);
            }
            Button retry = button(manual ? "核对网页工时" : uncertain ? "核对后重试…" : "重试", "subtle");
            retry.setOnAction(event -> retry(this));
            recovery.getChildren().add(retry);
        }
    }

    private record Attempt(RowEditor editor, Row row, BigDecimal hours, boolean retryConfirmed, boolean verify) {}
    private record Result(Attempt attempt, Throwable failure) {}
}
