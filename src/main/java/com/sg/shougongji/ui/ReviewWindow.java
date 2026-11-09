package com.sg.shougongji.ui;

import com.sg.shougongji.model.DayReview;
import com.sg.shougongji.model.WorkSession;
import com.sg.shougongji.model.WorkTask;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.service.ReviewService;
import com.sg.shougongji.service.TimerService;
import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.sg.shougongji.ui.UiSupport.bindSave;
import static com.sg.shougongji.ui.UiSupport.button;
import static com.sg.shougongji.ui.UiSupport.clockDisplay;
import static com.sg.shougongji.ui.UiSupport.field;
import static com.sg.shougongji.ui.UiSupport.form;
import static com.sg.shougongji.ui.UiSupport.formatTime;
import static com.sg.shougongji.ui.UiSupport.glassScene;
import static com.sg.shougongji.ui.UiSupport.icon;
import static com.sg.shougongji.ui.UiSupport.label;
import static com.sg.shougongji.ui.UiSupport.parseTime;
import static com.sg.shougongji.ui.UiSupport.perform;
import static com.sg.shougongji.ui.UiSupport.spacer;

final class ReviewWindow {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuu年M月d日", Locale.SIMPLIFIED_CHINESE);
    private static final String CLOCK_ICON = "M7 1 A6 6 0 1 1 7 13 A6 6 0 1 1 7 1 M7 3.5 V7 L9.5 8.5";
    private static final PseudoClass COMPACT = PseudoClass.getPseudoClass("compact");
    private static final PseudoClass NARROW = PseudoClass.getPseudoClass("narrow");
    private static final PseudoClass LAST_RECORD = PseudoClass.getPseudoClass("last-record");

    private final DesktopApplication app;
    private final TimerService timer;
    private final ReviewService reviewService;
    private final YunxiaoDialogs yunxiao;
    private final Clock clock;
    private final Stage timerStage;
    private final Stage stage = new Stage();
    private final VBox timeline = new VBox();
    private final VBox summary = new VBox();
    private final Label total = label("", "metric");
    private final Label taskCount = label("", "stat-value");
    private final Label segmentCount = label("", "stat-value");
    private final Label unassignedCount = label("", "stat-value");
    private final Label estimatedTime = label("", "muted", "wrap");
    private final Label status = label("", "muted", "wrap");
    private final Label activeNotice = label("", "notice");
    private final DatePicker date;
    private final Button copy = button("复制清单", "primary");

    private DayReview review;
    private Map<Long, Binding> cloudBindings = Map.of();

    ReviewWindow(DesktopApplication app, TimerService timer, ReviewService reviewService, Clock clock,
                 Stage timerStage, YunxiaoDialogs yunxiao) {
        this.app = app;
        this.timer = timer;
        this.reviewService = reviewService;
        this.yunxiao = yunxiao;
        this.clock = clock;
        this.timerStage = timerStage;
        date = new DatePicker(LocalDate.now(clock));
        date.setEditable(false);
        date.setPrefWidth(150);
        date.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate value) {
                return value == null ? "" : DAY.format(value);
            }

            @Override
            public LocalDate fromString(String value) {
                return LocalDate.parse(value, DAY);
            }
        });
        date.setAccessibleText("选择收工日期");
        date.valueProperty().addListener((property, old, value) -> {
            if (value == null) {
                date.setValue(old);
            } else {
                perform(stage, this::refresh);
            }
        });
        stage.setTitle("收工页 · 收工记");
        HBox root = content();
        Scene scene = glassScene(root, 920, 620);
        app.addWindowShortcuts(scene);
        scene.widthProperty().addListener((property, previous, width) ->
                root.pseudoClassStateChanged(NARROW, width.doubleValue() < 720));
        scene.heightProperty().addListener((property, previous, height) ->
                root.pseudoClassStateChanged(COMPACT, height.doubleValue() < 560));
        stage.setScene(scene);
        stage.setMinWidth(620);
        stage.setMinHeight(410);
        stage.setOnHidden(event -> timerStage.setAlwaysOnTop(true));
        stage.iconifiedProperty().addListener((property, previous, minimized) ->
                timerStage.setAlwaysOnTop(minimized || !stage.isShowing()));
    }

    void show() throws SQLException {
        refresh();
        timerStage.setAlwaysOnTop(false);
        stage.show();
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    private HBox content() {
        RadioButton timelineTab = new RadioButton("时间线");
        RadioButton summaryTab = new RadioButton("事项汇总");
        VBox sidebar = createSidebar(timelineTab, summaryTab);
        HBox header = createHeader();
        HBox overview = createOverview();
        configureActiveNotice();
        VBox records = createRecordsPanel(timelineTab, summaryTab);

        VBox main = new VBox(16, header, overview, activeNotice, records, status);
        main.getStyleClass().add("review-main");
        main.setMinWidth(0);
        HBox.setHgrow(main, Priority.ALWAYS);
        HBox root = new HBox(sidebar, main);
        root.getStyleClass().add("review-page");
        return root;
    }

    private HBox createHeader() {
        Button previous = button("", "date-arrow");
        previous.setGraphic(icon("M8 3 L4 7 L8 11"));
        previous.setAccessibleText("前一天");
        previous.setOnAction(event -> date.setValue(date.getValue().minusDays(1)));
        Button next = button("", "date-arrow");
        next.setGraphic(icon("M4 3 L8 7 L4 11"));
        next.setAccessibleText("后一天");
        next.setOnAction(event -> date.setValue(date.getValue().plusDays(1)));
        Button today = button("今天", "toolbar-button");
        today.setOnAction(event -> date.setValue(LocalDate.now(clock)));
        HBox dateControls = new HBox(previous, date, next);
        dateControls.setAlignment(Pos.CENTER_LEFT);
        dateControls.getStyleClass().add("date-controls");
        HBox navigation = new HBox(8, dateControls, today);
        navigation.setAlignment(Pos.CENTER_LEFT);
        HBox header = new HBox(10, label("收工页", "page-title"), spacer(), navigation);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private VBox createSidebar(RadioButton timelineTab, RadioButton summaryTab) {
        timelineTab.setGraphic(icon(CLOCK_ICON));
        summaryTab.setGraphic(icon("M2 2.5 H12 M2 7 H12 M2 11.5 H9"));
        ToggleGroup tabs = new ToggleGroup();
        for (RadioButton tab : List.of(timelineTab, summaryTab)) {
            tab.getStyleClass().add("sidebar-link");
            tab.setToggleGroup(tabs);
            tab.setMaxWidth(Double.MAX_VALUE);
        }
        timelineTab.setSelected(true);

        Button cloudSettings = button("云效设置", "sidebar-link");
        cloudSettings.setGraphic(icon("M1.5 9 A3 3 0 0 1 4.5 6 A3.7 3.7 0 0 1 11.5 6.5 A2.5 2.5 0 0 1 11 11.5 H4 A2.5 2.5 0 0 1 1.5 9"));
        cloudSettings.setMaxWidth(Double.MAX_VALUE);
        cloudSettings.setOnAction(event -> perform(stage, () -> yunxiao.configure(stage)));
        Region sidebarGap = new Region();
        VBox.setVgrow(sidebarGap, Priority.ALWAYS);
        StackPane brandIcon = new StackPane(icon(CLOCK_ICON));
        brandIcon.getStyleClass().add("review-brand-icon");
        HBox brand = new HBox(9, brandIcon, label("收工记", "sidebar-title"));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("review-brand");
        VBox sidebar = new VBox(5, brand, timelineTab, summaryTab, sidebarGap, cloudSettings);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private HBox createOverview() {
        estimatedTime.managedProperty().bind(estimatedTime.visibleProperty());
        VBox overviewMetric = new VBox(3, label("已结束工时", "review-metric-label"), total, estimatedTime);
        overviewMetric.setAlignment(Pos.CENTER_LEFT);
        overviewMetric.setMinWidth(0);
        HBox.setHgrow(overviewMetric, Priority.ALWAYS);
        SVGPath emblem = icon(CLOCK_ICON);
        emblem.setScaleX(1.55);
        emblem.setScaleY(1.55);
        StackPane timeEmblem = new StackPane(emblem);
        timeEmblem.getStyleClass().add("review-time-emblem");
        HBox overview = new HBox(14, timeEmblem, overviewMetric, stat("事项", taskCount),
                stat("时间段", segmentCount), stat("待归属", unassignedCount));
        overview.setAlignment(Pos.CENTER_LEFT);
        overview.getStyleClass().add("review-overview");
        return overview;
    }

    private void configureActiveNotice() {
        activeNotice.managedProperty().bind(activeNotice.visibleProperty());
        activeNotice.setMaxWidth(Double.MAX_VALUE);
        activeNotice.setMinWidth(0);
        Region noticeDot = new Region();
        noticeDot.getStyleClass().add("status-dot");
        activeNotice.setGraphic(noticeDot);
        activeNotice.setGraphicTextGap(8);
        Tooltip activeTooltip = new Tooltip();
        activeTooltip.textProperty().bind(activeNotice.textProperty());
        activeNotice.setTooltip(activeTooltip);
    }

    private VBox createRecordsPanel(RadioButton timelineTab, RadioButton summaryTab) {
        Button add = button("补录时间段", "secondary");
        add.setGraphic(icon("M7 2 V12 M2 7 H12"));
        add.setOnAction(event -> perform(stage, () -> editSession(null)));
        copy.setOnAction(event -> copyList());
        ScrollPane timelineView = scroll(timeline);
        timelineView.visibleProperty().bind(timelineTab.selectedProperty());
        timelineView.managedProperty().bind(timelineView.visibleProperty());
        ScrollPane summaryView = scroll(summary);
        summaryView.visibleProperty().bind(summaryTab.selectedProperty());
        summaryView.managedProperty().bind(summaryView.visibleProperty());
        StackPane records = new StackPane(timelineView, summaryView);
        VBox.setVgrow(records, Priority.ALWAYS);
        Label listTitle = label("", "panel-title");
        listTitle.textProperty().bind(Bindings.when(timelineTab.selectedProperty())
                .then("时间线").otherwise("事项汇总"));
        HBox listHeader = new HBox(8, listTitle, spacer(), add, copy);
        listHeader.setAlignment(Pos.CENTER_LEFT);
        listHeader.getStyleClass().add("record-toolbar");
        VBox sheet = new VBox(9, listHeader, records);
        VBox.setVgrow(sheet, Priority.ALWAYS);
        return sheet;
    }

    private static VBox stat(String title, Label value) {
        VBox column = new VBox(3, value, label(title, "muted"));
        column.setAlignment(Pos.CENTER_LEFT);
        column.getStyleClass().add("stat");
        return column;
    }

    void refresh() throws SQLException {
        ReviewService.Overview overview = reviewService.loadOverview(date.getValue());
        review = overview.review();
        cloudBindings = overview.bindings();
        long seconds = review.seconds();
        total.setText(clockDisplay(seconds));
        total.setAccessibleText(DayReview.duration(seconds));
        long estimated = review.estimatedSeconds();
        estimatedTime.setVisible(estimated > 0);
        estimatedTime.setText(estimated > 0 ? "含估算 " + DayReview.duration(estimated) : "");
        taskCount.setText(Integer.toString(review.entries().size()));
        segmentCount.setText(Integer.toString(review.segments().size()));
        long unassigned = review.entries().stream().filter(entry -> workItem(entry.task()).isBlank()).count();
        unassignedCount.setText(Long.toString(unassigned));

        WorkSession active = overview.activeSession();
        long until = date.getValue().plusDays(1).atStartOfDay(clock.getZone()).toEpochSecond();
        boolean showNotice = active != null && active.start() < until && !date.getValue().isAfter(LocalDate.now(clock));
        activeNotice.setVisible(showNotice);
        if (showNotice) {
            activeNotice.setText("「" + active.task().title() + "」正在计时，结束后计入本日汇总。");
        }

        timeline.getChildren().setAll(review.segments().stream().map(this::segmentCard).toList());
        summary.getChildren().setAll(review.entries().stream().map(entry -> entryCard(entry,
                active != null && active.task().id() == entry.task().id())).toList());
        if (review.segments().isEmpty()) {
            timeline.getChildren().add(emptyRecords());
            summary.getChildren().add(emptyRecords());
        } else {
            timeline.getChildren().getLast().pseudoClassStateChanged(LAST_RECORD, true);
            summary.getChildren().getLast().pseudoClassStateChanged(LAST_RECORD, true);
        }
        copy.setDisable(review.entries().isEmpty());
        status.setText("工作时区  " + clock.getZone().getId());
    }

    private HBox segmentCard(DayReview.Segment segment) {
        WorkSession session = segment.session();
        Button edit = button("编辑", "subtle");
        edit.setAccessibleText("编辑 " + session.task().title());
        edit.setOnAction(event -> perform(stage, () -> editSession(session)));
        VBox times = new VBox(3, label(TIME.format(Instant.ofEpochSecond(segment.start()).atZone(clock.getZone())), "start-time"),
                label("至 " + endTime(segment), "muted"));
        times.getStyleClass().add("timeline-times");
        VBox details = new VBox(4, label(session.task().title(), "section-title", "wrap"),
                label(segmentDetails(segment), "muted", "wrap"));
        if (!session.note().isBlank()) {
            details.getChildren().add(label(session.note(), "note", "wrap"));
        }
        details.setMinWidth(0);
        HBox.setHgrow(details, Priority.ALWAYS);
        edit.setMinWidth(Region.USE_PREF_SIZE);
        Region marker = new Region();
        marker.getStyleClass().add("timeline-marker");
        HBox card = new HBox(12, marker, times, details, edit);
        card.getStyleClass().add("segment-card");
        return card;
    }

    private TitledPane entryCard(DayReview.Entry entry, boolean timing) {
        WorkTask task = entry.task();
        Binding binding = cloudBindings.get(task.id());
        String workItem = workItem(task);
        String relation = workItem.isBlank() ? "待归属" : workItem;
        Button link = button(binding != null ? "手动关联…"
                : task.workItem().isBlank() ? "关联工作项" : "编辑工作项", "secondary");
        link.setOnAction(event -> editWorkItem(task));
        Button sync = button(binding == null ? "关联已有云效任务…" : "云效工时…",
                binding == null ? "secondary" : "primary");
        sync.setOnAction(event -> perform(stage, () -> {
            yunxiao.show(stage, entry, review.date(), binding);
            refresh();
        }));
        Button delete = button("删除事项…", "subtle");
        delete.setAccessibleText("删除 " + task.title());
        delete.setDisable(timing);
        delete.setOnAction(event -> app.confirmDeleteTask(stage, task));
        FlowPane actions = new FlowPane(8, 6, sync, link, delete);
        if (binding == null) {
            Button create = button("新建云效任务…", "primary");
            create.setOnAction(event -> perform(stage, () -> {
                yunxiao.create(stage, entry, review.date());
                refresh();
            }));
            actions.getChildren().addFirst(create);
        }
        VBox details = new VBox(8, label(relation, "muted", "wrap"), actions);
        for (DayReview.Segment segment : entry.segments()) {
            VBox detail = new VBox(3, label(timeRange(segment) + "  ·  " + segmentDetails(segment), "note", "wrap"));
            detail.getStyleClass().add("entry-segment");
            if (!segment.session().note().isBlank()) {
                detail.getChildren().add(label(segment.session().note(), "muted", "wrap"));
            }
            details.getChildren().add(detail);
        }
        TitledPane pane = new TitledPane("", details);
        Label title = label(task.title(), "section-title");
        title.setMinWidth(0);
        Label meta = label(entry.segments().size() + " 段"
                + (entry.estimatedSeconds() == 0 ? "" : "  ·  含估算")
                + (workItem.isBlank() ? "  ·  待归属" : ""), "muted");
        meta.setMinWidth(0);
        VBox description = new VBox(3, title, meta);
        description.setMinWidth(0);
        HBox.setHgrow(description, Priority.ALWAYS);
        Label duration = label(DayReview.duration(entry.seconds()), "entry-duration");
        duration.setMinWidth(Region.USE_PREF_SIZE);
        HBox heading = new HBox(12, description, duration);
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.prefWidthProperty().bind(pane.widthProperty().subtract(54));
        pane.setGraphic(heading);
        pane.setMinWidth(0);
        pane.setAccessibleText(task.title() + "，" + duration.getText() + "，" + meta.getText());
        pane.setTooltip(new Tooltip(task.title()));
        pane.setExpanded(false);
        pane.setAnimated(false);
        return pane;
    }

    private String timeRange(DayReview.Segment segment) {
        String start = TIME.format(Instant.ofEpochSecond(segment.start()).atZone(clock.getZone()));
        return start + " – " + endTime(segment);
    }

    private String endTime(DayReview.Segment segment) {
        long dayEnd = date.getValue().plusDays(1).atStartOfDay(clock.getZone()).toEpochSecond();
        return (segment.end() == dayEnd ? "次日 " : "")
                + TIME.format(Instant.ofEpochSecond(segment.end()).atZone(clock.getZone()));
    }

    private static String segmentDetails(DayReview.Segment segment) {
        WorkSession session = segment.session();
        return DayReview.duration(segment.seconds()) + (session.manual() ? "  ·  补录" : "  ·  计时")
                + (session.estimated() ? "  ·  估算" : "");
    }

    private void editSession(WorkSession original) throws SQLException {
        ComboBox<String> task = new ComboBox<>();
        task.getItems().setAll(timer.tasks().stream().map(WorkTask::title).toList());
        task.setEditable(true);
        task.setMaxWidth(Double.MAX_VALUE);
        task.setPromptText("选择已有事项，或输入新事项");
        TextField start = new TextField();
        TextField end = new TextField();
        TextArea note = new TextArea();
        note.setPromptText("例如：定位到导出任务缺少权限");
        note.setPrefRowCount(3);
        note.setWrapText(true);
        CheckBox estimated = new CheckBox("这段时间是估算");
        LocalDate selected = date.getValue();
        if (original != null) {
            task.setValue(original.task().title());
            start.setText(formatTime(original.start(), clock.getZone()));
            end.setText(formatTime(original.end(), clock.getZone()));
            note.setText(original.note());
            estimated.setSelected(original.estimated());
        }
        start.setPromptText("例如 9:30");
        end.setPromptText("例如 10:15");
        VBox startField = field("开始时间", start);
        VBox endField = field("结束时间", end);
        HBox.setHgrow(startField, Priority.ALWAYS);
        HBox.setHgrow(endField, Priority.ALWAYS);
        VBox timeFields = new VBox(5, new HBox(10, startField, endField),
                label("只填时分会记在 " + selected + "，跨日请填写日期。", "muted", "wrap"));
        VBox fields = new VBox(12, field("事项", task), timeFields, field("备注或结果", note), estimated);
        Dialog<ButtonType> dialog = form(stage, original == null ? "补录时间段" : "编辑时间段", fields, "保存");
        bindSave(dialog, () -> {
            Instant from = original != null && start.getText().equals(formatTime(original.start(), clock.getZone()))
                    ? Instant.ofEpochSecond(original.start()) : parseTime(start.getText(), clock.getZone(), selected);
            Instant to = original != null && end.getText().equals(formatTime(original.end(), clock.getZone()))
                    ? Instant.ofEpochSecond(original.end()) : parseTime(end.getText(), clock.getZone(), selected);
            timer.saveSession(original, task.getEditor().getText(), from, to, note.getText(), estimated.isSelected());
        });
        if (dialog.showAndWait().filter(dialog.getDialogPane().getButtonTypes().getFirst()::equals).isPresent()) {
            app.refresh();
        }
    }

    private void editWorkItem(WorkTask task) {
        TextField value = new TextField(task.workItem());
        value.setPromptText("粘贴云效工作项链接或输入编号");
        Dialog<ButtonType> dialog = form(stage, "关联工作项",
                new VBox(8, label(task.title(), "section-title", "wrap"), value,
                        label("关联应用于这个事项的所有时间段。留空可恢复为待归属。", "muted", "wrap")), "保存");
        bindSave(dialog, () -> timer.updateWorkItem(task.id(), value.getText()));
        if (dialog.showAndWait().filter(dialog.getDialogPane().getButtonTypes().getFirst()::equals).isPresent()) {
            perform(stage, this::refresh);
        }
    }

    private void copyList() {
        ClipboardContent content = new ClipboardContent();
        content.putString(review.clipboardText(this::workItem));
        status.setText(Clipboard.getSystemClipboard().setContent(content) ? "已复制收工清单。" : "复制失败，请重试。");
    }

    private String workItem(WorkTask task) {
        Binding binding = cloudBindings.get(task.id());
        return binding == null ? task.workItem() : "云效 · " + binding.label();
    }

    private static ScrollPane scroll(VBox items) {
        items.getStyleClass().add("review-records");
        ScrollPane scroll = new ScrollPane(items);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinHeight(0);
        return scroll;
    }

    private static VBox emptyRecords() {
        VBox empty = new VBox(5, label("当天暂无记录", "section-title"),
                label("完成计时或补录时间段后，记录会显示在这里。", "muted", "wrap"));
        empty.setAlignment(Pos.CENTER);
        empty.getStyleClass().add("review-empty");
        return empty;
    }
}
