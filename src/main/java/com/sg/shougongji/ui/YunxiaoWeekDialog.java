package com.sg.shougongji.ui;

import com.sg.shougongji.model.Yunxiao.ApiException;
import com.sg.shougongji.model.Yunxiao.ProtocolException;
import com.sg.shougongji.model.YunxiaoWeek;
import com.sg.shougongji.service.YunxiaoService;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;

import static com.sg.shougongji.ui.UiSupport.background;
import static com.sg.shougongji.ui.UiSupport.button;
import static com.sg.shougongji.ui.UiSupport.label;
import static com.sg.shougongji.ui.UiSupport.spacer;
import static com.sg.shougongji.ui.UiSupport.styleDialog;

final class YunxiaoWeekDialog {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("M/d");

    private final YunxiaoService connection;
    private final LocalDate today;
    private final LocalDate monday;
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final GridPane weekDays = new GridPane();
    private final VBox dayDetails = new VBox();
    private final VBox otherRecords = new VBox(6);
    private final ScrollPane scroll = new ScrollPane(new VBox(10, dayDetails, otherRecords));
    private final Label total = label("—", "week-total");
    private final Label account = label("", "section-title");
    private final Label status = label("", "muted", "wrap");
    private final Button refresh = button("刷新", "secondary");
    private LocalDate selectedDate;
    private Task<YunxiaoWeek> request;

    YunxiaoWeekDialog(Window owner, YunxiaoService connection, LocalDate today) {
        this.connection = connection;
        this.today = today;
        monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        dialog.initOwner(owner);
        dialog.setTitle("本周云效工时");
        dialog.setHeaderText("本周云效工时");
        dialog.setResizable(true);
        styleDialog(dialog, 740);
        dialog.getDialogPane().getStyleClass().add("yunxiao-week");
        dialog.setOnShown(event -> {
            Stage window = (Stage) dialog.getDialogPane().getScene().getWindow();
            window.setMinWidth(620);
            window.setMinHeight(420);
        });
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.setOnHidden(event -> {
            if (request != null) {
                request.cancel();
            }
        });

        refresh.setOnAction(event -> load());
        HBox header = new HBox(8, label(monday + " 至 " + monday.plusDays(6), "muted"), spacer(), refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        status.managedProperty().bind(status.textProperty().isNotEmpty());
        weekDays.setHgap(7);
        weekDays.setMinHeight(92);
        for (int day = 0; day < 7; day++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / 7);
            weekDays.getColumnConstraints().add(column);
        }
        dayDetails.getStyleClass().add("week-details");
        dayDetails.visibleProperty().bind(Bindings.isNotEmpty(dayDetails.getChildren()));
        dayDetails.managedProperty().bind(dayDetails.visibleProperty());
        otherRecords.managedProperty().bind(Bindings.isNotEmpty(otherRecords.getChildren()));
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(225);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        dialog.getDialogPane().setContent(new VBox(10, header, createOverview(), status, weekDays, scroll));
    }

    private HBox createOverview() {
        HBox amount = new HBox(5, total, label("小时", "muted"));
        amount.setAlignment(Pos.BASELINE_LEFT);
        VBox metric = new VBox(2, label("本周逐日合计", "muted"), amount);
        account.managedProperty().bind(account.textProperty().isNotEmpty());
        account.setMinWidth(0);
        VBox scope = new VBox(4, account, label("已关联工作项 · 本人实际工时", "muted"));
        scope.setAlignment(Pos.CENTER_RIGHT);
        HBox overview = new HBox(12, metric, spacer(), scope);
        overview.setAlignment(Pos.CENTER_LEFT);
        overview.getStyleClass().add("week-overview");
        return overview;
    }

    void show() {
        dialog.show();
        load();
    }

    private void load() {
        refresh.setDisable(true);
        weekDays.getChildren().clear();
        dayDetails.getChildren().clear();
        otherRecords.getChildren().clear();
        total.setText("—");
        account.setText("");
        status.setText("正在读取云效工时…");
        request = background(() -> connection.weekEffort(monday), week -> {
            request = null;
            refresh.setDisable(false);
            render(week);
        }, error -> {
            request = null;
            refresh.setDisable(false);
            status.setText(switch (error) {
                case SQLException failure -> "无法读取本地的工作项关联。";
                case ProtocolException failure -> "无法识别云效返回的账号信息。";
                case IOException failure when !(failure instanceof ApiException) -> "暂时无法连接云效，请稍后刷新。";
                default -> YunxiaoDialogs.errorText(error);
            });
        });
    }

    private void render(YunxiaoWeek week) {
        status.setText("");
        if (week.workItemCount() == 0) {
            status.setText("当前连接下没有已关联的工作项。");
            return;
        }
        account.setText(week.accountName());
        int unavailable = week.unavailableItems().size();
        if (unavailable == week.workItemCount()) {
            status.setText("无法读取已关联工作项的工时。请检查实际工时只读权限和工作项访问权限。");
        } else {
            total.setText(hourValue(week.dailyHours()));
            if (unavailable > 0 || week.unreadableRecords() > 0) {
                status.setText("汇总可能不完整。未能读取 " + unavailable + " 个工作项，"
                        + week.unreadableRecords() + " 条记录缺少有效信息。");
            }
            showDays(week.days());
            if (!week.multiDay().isEmpty()) {
                VBox spanning = new VBox(8, label("跨日记录按原登记区间显示，逐日合计统计单日登记。", "muted", "wrap"));
                week.multiDay().forEach(registration -> spanning.getChildren().add(registrationRow(registration, true)));
                otherRecords.getChildren().add(disclosure("跨日登记 · " + week.multiDay().size() + " 条", spanning));
            }
        }
        if (unavailable > 0) {
            VBox details = new VBox(8);
            week.unavailableItems().forEach(item -> details.getChildren().add(label(item, "muted", "wrap")));
            otherRecords.getChildren().add(disclosure("读取失败的工作项（" + unavailable + "）", details));
        }
    }

    private void showDays(List<YunxiaoWeek.Day> days) {
        LocalDate preferred = selectedDate;
        if (preferred == null) {
            preferred = days.stream().filter(day -> !day.records().isEmpty() && day.date().equals(today))
                    .map(YunxiaoWeek.Day::date).findFirst().orElseGet(() -> days.reversed().stream()
                            .filter(day -> !day.records().isEmpty()).map(YunxiaoWeek.Day::date).findFirst().orElse(today));
        }
        ToggleGroup selection = new ToggleGroup();
        for (int index = 0; index < days.size(); index++) {
            YunxiaoWeek.Day day = days.get(index);
            RadioButton card = dayCard(day);
            card.setToggleGroup(selection);
            card.selectedProperty().addListener((property, before, selected) -> {
                if (selected) {
                    selectedDate = day.date();
                    showDayDetails(day);
                }
            });
            weekDays.add(card, index, 0);
            if (day.date().equals(preferred)) {
                card.setSelected(true);
            }
        }
    }

    private RadioButton dayCard(YunxiaoWeek.Day day) {
        Label weekday = label(WEEKDAY.format(day.date()), "week-day-name");
        Label date = label(SHORT_DATE.format(day.date()), "muted");
        Label value = label(hourValue(day.hours()), "week-day-hours");
        HBox amount = new HBox(3, value, label("小时", "week-day-unit"));
        amount.setAlignment(Pos.BASELINE_CENTER);
        VBox content = new VBox(3, weekday, date, amount);
        content.setAlignment(Pos.CENTER);
        RadioButton card = new RadioButton();
        card.getStyleClass().add("week-day");
        if (day.records().isEmpty()) {
            card.getStyleClass().add("week-day-empty");
        }
        if (day.date().equals(today)) {
            card.getStyleClass().add("week-day-today");
        }
        card.setGraphic(content);
        card.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        card.setAccessibleText(DAY.format(day.date()) + "，" + hours(day.hours()));
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private void showDayDetails(YunxiaoWeek.Day day) {
        HBox heading = new HBox(8, label(DAY.format(day.date()), "section-title"), spacer(),
                label(hours(day.hours()), "entry-duration"));
        heading.setAlignment(Pos.CENTER_LEFT);
        dayDetails.getChildren().setAll(heading);
        if (day.records().isEmpty()) {
            dayDetails.getChildren().add(label("当天没有登记工时。", "week-empty", "muted"));
        } else {
            day.records().forEach(registration -> dayDetails.getChildren().add(registrationRow(registration, false)));
        }
        scroll.setVvalue(0);
    }

    private static VBox registrationRow(YunxiaoWeek.Registration registration, boolean spanning) {
        Label workItem = label(registration.workItem(), "section-title", "wrap");
        workItem.setMinWidth(0);
        workItem.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(workItem, Priority.ALWAYS);
        Label duration = label(hours(registration.hours()), "entry-duration");
        duration.setMinWidth(Region.USE_PREF_SIZE);
        HBox heading = new HBox(12, workItem, duration);
        heading.setAlignment(Pos.CENTER_LEFT);
        VBox row = new VBox(4, heading);
        row.getStyleClass().add("week-registration");
        if (spanning) {
            row.getChildren().add(label(registration.start() + " 至 " + registration.end(), "muted", "wrap"));
        }
        if (!registration.category().isBlank()) {
            row.getChildren().add(label(registration.category(), "muted", "wrap"));
        }
        String description = registration.description();
        if (description.startsWith("由收工记登记\n收工记#")) {
            description = "由收工记登记";
        }
        if (!description.isBlank()) {
            row.getChildren().add(label(description, "note", "wrap"));
        }
        return row;
    }

    private static TitledPane disclosure(String title, VBox content) {
        TitledPane pane = new TitledPane(title, content);
        pane.setAnimated(false);
        pane.setExpanded(false);
        return pane;
    }

    private static String hours(BigDecimal hours) {
        return hourValue(hours) + " 小时";
    }

    private static String hourValue(BigDecimal hours) {
        return hours.stripTrailingZeros().toPlainString();
    }
}
