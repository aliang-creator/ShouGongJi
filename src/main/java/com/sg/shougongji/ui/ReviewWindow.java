package com.sg.shougongji.ui;

import com.sg.shougongji.model.DayReview;
import com.sg.shougongji.model.WorkSchedule;
import com.sg.shougongji.model.WorkSession;
import com.sg.shougongji.model.WorkTask;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.service.ReviewService;
import com.sg.shougongji.service.TimerService;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.sg.shougongji.ui.UiSupport.appScene;
import static com.sg.shougongji.ui.UiSupport.background;
import static com.sg.shougongji.ui.UiSupport.bindSave;
import static com.sg.shougongji.ui.UiSupport.button;
import static com.sg.shougongji.ui.UiSupport.clockDisplay;
import static com.sg.shougongji.ui.UiSupport.field;
import static com.sg.shougongji.ui.UiSupport.form;
import static com.sg.shougongji.ui.UiSupport.icon;
import static com.sg.shougongji.ui.UiSupport.label;
import static com.sg.shougongji.ui.UiSupport.perform;
import static com.sg.shougongji.ui.UiSupport.spacer;

final class ReviewWindow {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter WORK_TIME = DateTimeFormatter.ofPattern("H:mm")
            .withResolverStyle(ResolverStyle.STRICT);
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
    private final StackPane layers = new StackPane();
    private final HBox layout;
    private final ListView<DayReview.Segment> timeline = new ListView<>();
    private final ListView<DayReview.Entry> summary = new ListView<>();
    private final Set<Long> expandedTasks = new HashSet<>();
    private final Label total = label("", "metric");
    private final Label taskCount = label("", "stat-value");
    private final Label segmentCount = label("", "stat-value");
    private final Label unassignedCount = label("", "stat-value");
    private final Label estimatedTime = label("", "muted", "wrap");
    private final Label status = label("", "muted", "wrap");
    private final Label activeNotice = label("", "notice");
    private final DatePicker date;
    private final Button add = button("补录工时", "secondary");
    private final Button copy = button("复制清单", "primary");

    private DayReview review;
    private Task<ReviewService.Overview> loadRequest;
    private ReviewService.Overview displayedOverview;
    private long reviewRevision;
    private long runningTaskId = -1;
    private EffortOverlay effortOverlay;
    private Node focusBeforeEffort;
    private Map<Long, Binding> cloudBindings = Map.of();
    private Set<Long> cloudPending = Set.of();

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
                refresh();
            }
        });
        stage.setTitle("收工页 · 收工记");
        layout = content();
        layers.getChildren().add(layout);
        Scene scene = appScene(layers, 920, 620);
        app.addWindowShortcuts(scene);
        scene.widthProperty().addListener((property, previous, width) ->
                layout.pseudoClassStateChanged(NARROW, width.doubleValue() < 720));
        scene.heightProperty().addListener((property, previous, height) ->
                layout.pseudoClassStateChanged(COMPACT, height.doubleValue() < 560));
        stage.setScene(scene);
        stage.setMinWidth(620);
        stage.setMinHeight(410);
        stage.setOnCloseRequest(event -> {
            if (effortOverlay != null && effortOverlay.isBusy()) {
                event.consume();
            }
        });
        stage.setOnHidden(event -> {
            if (effortOverlay != null) {
                effortOverlay.close();
            }
            cancelLoad();
            timerStage.setAlwaysOnTop(true);
        });
        stage.iconifiedProperty().addListener((property, previous, minimized) ->
                timerStage.setAlwaysOnTop(minimized || !stage.isShowing()));
    }

    void show() {
        timerStage.setAlwaysOnTop(false);
        stage.show();
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
        refresh();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    boolean isEffortOpen() {
        return effortOverlay != null && stage.isShowing() && !stage.isIconified();
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

        Button weeklyEffort = button("本周云效工时", "sidebar-link");
        weeklyEffort.setGraphic(icon("M2 4 H12 V12 H2 Z M2 6.5 H12 M5 2 V4 M9 2 V4 M5 9 H9"));
        weeklyEffort.setMaxWidth(Double.MAX_VALUE);
        weeklyEffort.setOnAction(event -> perform(stage, () -> yunxiao.showWeek(stage, LocalDate.now(clock))));
        Button scheduleSettings = button("上班时段", "sidebar-link");
        scheduleSettings.setGraphic(icon(CLOCK_ICON));
        scheduleSettings.setMaxWidth(Double.MAX_VALUE);
        scheduleSettings.setOnAction(event -> perform(stage, this::editWorkSchedule));
        Button cloudSettings = button("云效设置", "sidebar-link");
        cloudSettings.setGraphic(icon("M1.5 9 A3 3 0 0 1 4.5 6 A3.7 3.7 0 0 1 11.5 6.5 A2.5 2.5 0 0 1 11 11.5 H4 A2.5 2.5 0 0 1 1.5 9"));
        cloudSettings.setMaxWidth(Double.MAX_VALUE);
        cloudSettings.setOnAction(event -> perform(stage, () -> {
            yunxiao.configure(stage);
            app.requestAutoSync();
        }));
        Region sidebarGap = new Region();
        VBox.setVgrow(sidebarGap, Priority.ALWAYS);
        StackPane brandIcon = new StackPane(icon(CLOCK_ICON));
        brandIcon.getStyleClass().add("review-brand-icon");
        HBox brand = new HBox(9, brandIcon, label("收工记", "sidebar-title"));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("review-brand");
        VBox sidebar = new VBox(5, brand, timelineTab, summaryTab, weeklyEffort, sidebarGap, scheduleSettings, cloudSettings);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private HBox createOverview() {
        estimatedTime.managedProperty().bind(estimatedTime.visibleProperty());
        VBox overviewMetric = new VBox(3, label("已记录工时", "review-metric-label"), total, estimatedTime);
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
        add.setGraphic(icon("M7 2 V12 M2 7 H12"));
        add.setOnAction(event -> perform(stage, () -> editWorkHours(null)));
        copy.setOnAction(event -> copyList());
        configureList(timeline, this::segmentCard);
        configureList(summary, entry -> entryCard(entry, entry.task().id() == runningTaskId));
        timeline.visibleProperty().bind(timelineTab.selectedProperty());
        timeline.managedProperty().bind(timeline.visibleProperty());
        summary.visibleProperty().bind(summaryTab.selectedProperty());
        summary.managedProperty().bind(summary.visibleProperty());
        StackPane records = new StackPane(timeline, summary);
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

    void refresh() {
        LocalDate requestedDay = date.getValue();
        cancelLoad();
        long revision = reviewRevision;
        if (review == null || !requestedDay.equals(review.date())) {
            displayedOverview = null;
            expandedTasks.clear();
            add.setDisable(true);
            copy.setDisable(true);
            activeNotice.setVisible(false);
            total.setText("—");
            estimatedTime.setVisible(false);
            taskCount.setText("—");
            segmentCount.setText("—");
            unassignedCount.setText("—");
            timeline.getItems().clear();
            summary.getItems().clear();
            timeline.setPlaceholder(label("正在读取记录…", "muted"));
            summary.setPlaceholder(label("正在读取记录…", "muted"));
            status.setText("");
        }
        loadRequest = background(() -> reviewService.loadOverview(requestedDay), overview -> {
            if (revision == reviewRevision) {
                loadRequest = null;
                render(overview);
            }
        }, failure -> {
            if (revision == reviewRevision) {
                loadRequest = null;
                status.setText("无法读取收工记录，请重新选择日期后重试。");
                timeline.setPlaceholder(label("读取失败", "muted"));
                summary.setPlaceholder(label("读取失败", "muted"));
            }
        });
    }

    private void cancelLoad() {
        reviewRevision++;
        if (loadRequest != null) {
            loadRequest.cancel();
            loadRequest = null;
        }
    }

    private void render(ReviewService.Overview overview) {
        if (overview.equals(displayedOverview)) {
            return;
        }
        displayedOverview = overview;
        review = overview.review();
        cloudBindings = overview.bindings();
        cloudPending = overview.pendingEffortTasks();
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
        runningTaskId = active == null ? -1 : active.task().id();
        long until = review.date().plusDays(1).atStartOfDay(clock.getZone()).toEpochSecond();
        boolean showNotice = active != null && active.start() < until && !review.date().isAfter(LocalDate.now(clock));
        activeNotice.setVisible(showNotice);
        if (showNotice) {
            activeNotice.setText("「" + active.task().title() + "」正在计时，结束后计入本日汇总。");
        }

        timeline.setPlaceholder(emptyRecords());
        summary.setPlaceholder(emptyRecords());
        timeline.getItems().setAll(review.segments());
        summary.getItems().setAll(review.entries());
        add.setDisable(false);
        copy.setDisable(review.entries().isEmpty());
        status.setText("工作时区  " + clock.getZone().getId());
    }

    private HBox segmentCard(DayReview.Segment segment) {
        WorkSession session = segment.session();
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
        Region marker = new Region();
        marker.getStyleClass().add("timeline-marker");
        HBox card = new HBox(12, marker, times, details, editHoursButton(session.task()));
        card.getStyleClass().add("segment-card");
        return card;
    }

    private TitledPane entryCard(DayReview.Entry entry, boolean timing) {
        WorkTask task = entry.task();
        Binding binding = cloudBindings.get(task.id());
        boolean pending = cloudPending.contains(task.id());
        String workItem = workItem(task);
        String relation = workItem.isBlank() ? "待归属" : workItem;
        LocalDate entryDate = review.date();
        TitledPane pane = new TitledPane();
        Label title = label(task.title(), "section-title");
        title.setMinWidth(0);
        Label meta = label(entry.segments().size() + " 段"
                + (entry.estimatedSeconds() == 0 ? "" : "  ·  含估算")
                + (pending ? "  ·  云效待同步" : workItem.isBlank() ? "  ·  待归属" : ""), "muted");
        meta.setMinWidth(0);
        VBox description = new VBox(3, title, meta);
        description.setMinWidth(0);
        HBox.setHgrow(description, Priority.ALWAYS);
        Label duration = label(DayReview.duration(entry.seconds()), "entry-duration");
        duration.setMinWidth(Region.USE_PREF_SIZE);
        HBox heading = new HBox(12, description, editHoursButton(task), duration);
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.prefWidthProperty().bind(pane.widthProperty().subtract(54));
        pane.setGraphic(heading);
        pane.setMinWidth(0);
        pane.setAccessibleText(task.title() + "，" + duration.getText() + "，" + meta.getText());
        pane.setAnimated(false);
        pane.setExpanded(expandedTasks.contains(task.id()));
        if (pane.isExpanded()) {
            pane.setContent(entryDetails(entry, entryDate, timing, binding, pending, relation));
        }
        pane.expandedProperty().addListener((observable, previous, expanded) -> {
            if (expanded) {
                expandedTasks.add(task.id());
                if (pane.getContent() == null) {
                    pane.setContent(entryDetails(entry, entryDate, timing, binding, pending, relation));
                }
            } else {
                expandedTasks.remove(task.id());
            }
        });
        return pane;
    }

    private VBox entryDetails(DayReview.Entry entry, LocalDate entryDate, boolean timing, Binding binding,
                              boolean pending, String relation) {
        WorkTask task = entry.task();
        Runnable refreshAfterEdit = () -> perform(stage, app::refresh);
        Consumer<Binding> showAfterBinding = newBinding -> perform(stage, () -> {
            app.refresh();
            showEffort(entry, entryDate, newBinding, refreshAfterEdit);
        });
        Button link = button(binding != null ? "手动关联…"
                : task.workItem().isBlank() ? "关联工作项" : "编辑工作项", "secondary");
        link.setOnAction(event -> editWorkItem(task));
        Button sync = button(binding == null ? pending ? "重试云效同步" : "关联已有云效任务…" : "云效工时…",
                binding == null ? "secondary" : "primary");
        sync.setOnAction(event -> perform(stage, () -> {
            if (binding != null) {
                showEffort(entry, entryDate, binding, refreshAfterEdit);
            } else if (pending) {
                app.retryAutoSync(stage);
            } else {
                yunxiao.associate(stage, entry, entryDate, showAfterBinding);
            }
        }));
        Button delete = button("删除事项…", "subtle");
        delete.setAccessibleText("删除 " + task.title());
        delete.setDisable(timing);
        delete.setOnAction(event -> app.confirmDeleteTask(stage, task));
        FlowPane actions = new FlowPane(8, 6, sync, link, delete);
        if (binding != null) {
            Button editStatus = button("云效状态…", "secondary");
            editStatus.setOnAction(event -> perform(stage, () -> yunxiao.editStatus(stage, binding)));
            actions.getChildren().add(1, editStatus);
        } else if (pending) {
            Button recover = button("关联已创建的子任务…", "secondary");
            recover.setOnAction(event -> perform(stage, () -> yunxiao.linkAutomaticChild(stage, entry, entryDate,
                    () -> perform(stage, () -> {
                        app.refresh();
                        app.requestAutoSync();
                    }))));
            actions.getChildren().add(1, recover);
        } else {
            Button create = button("新建云效任务…", "primary");
            create.setOnAction(event -> perform(stage, () -> yunxiao.create(stage, entry, entryDate, showAfterBinding)));
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
        return details;
    }

    private Button editHoursButton(WorkTask task) {
        Button edit = button("编辑工时", "subtle");
        edit.setMinWidth(Region.USE_PREF_SIZE);
        edit.setAccessibleText("编辑当天工时，" + task.title());
        edit.setOnAction(event -> perform(stage, () -> {
            DayReview.Entry entry = review.entries().stream().filter(item -> item.task().id() == task.id())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("该事项当天的记录已变更，请刷新后重试。"));
            editWorkHours(entry);
        }));
        return edit;
    }

    private void showEffort(DayReview.Entry entry, LocalDate entryDate, Binding binding, Runnable onChanged)
            throws SQLException {
        if (effortOverlay != null) {
            return;
        }
        EffortOverlay panel = yunxiao.effortOverlay(stage, entry, entryDate, binding, this::closeEffort, onChanged);
        if (panel != null) {
            focusBeforeEffort = stage.getScene().getFocusOwner();
            effortOverlay = panel;
            layout.setDisable(true);
            layers.getChildren().add(panel);
        }
    }

    private void closeEffort(EffortOverlay panel) {
        layers.getChildren().remove(panel);
        effortOverlay = null;
        layout.setDisable(false);
        if (focusBeforeEffort != null && focusBeforeEffort.getScene() == stage.getScene()) {
            focusBeforeEffort.requestFocus();
        }
        focusBeforeEffort = null;
    }

    private String timeRange(DayReview.Segment segment) {
        String start = TIME.format(Instant.ofEpochSecond(segment.start()).atZone(clock.getZone()));
        return start + " – " + endTime(segment);
    }

    private String endTime(DayReview.Segment segment) {
        long dayEnd = review.date().plusDays(1).atStartOfDay(clock.getZone()).toEpochSecond();
        return (segment.end() == dayEnd ? "次日 " : "")
                + TIME.format(Instant.ofEpochSecond(segment.end()).atZone(clock.getZone()));
    }

    private static String segmentDetails(DayReview.Segment segment) {
        WorkSession session = segment.session();
        return DayReview.duration(segment.seconds()) + (session.manual() ? "  ·  补录" : "  ·  计时")
                + (session.estimated() ? "  ·  估算" : "");
    }

    private Map<String, WorkTask> taskChoices() throws SQLException {
        List<WorkTask> tasks = timer.tasks();
        Map<String, Long> titleCounts = tasks.stream()
                .collect(Collectors.groupingBy(WorkTask::title, Collectors.counting()));
        Map<String, WorkTask> choices = new LinkedHashMap<>();
        for (WorkTask option : tasks) {
            String name = option.title();
            if (titleCounts.get(name) > 1) {
                String context = workItem(option);
                name += " · " + (context.isBlank() ? "本地事项" : context);
            }
            while (choices.containsKey(name)) {
                name += " · " + option.id();
            }
            choices.put(name, option);
        }
        return choices;
    }

    private void editWorkHours(DayReview.Entry original) throws SQLException {
        Map<String, WorkTask> choices = original == null ? taskChoices() : Map.of();
        ComboBox<String> task = new ComboBox<>();
        task.getItems().setAll(choices.keySet());
        task.setEditable(true);
        task.setMaxWidth(Double.MAX_VALUE);
        task.setPromptText("选择已有事项，或输入新事项");
        TextField hours = new TextField();
        hours.setPromptText("例如 3 或 1.5");
        TextArea note = new TextArea();
        note.setPromptText("例如：定位到导出任务缺少权限");
        note.setPrefRowCount(3);
        note.setWrapText(true);
        CheckBox estimated = new CheckBox("工时为估算");
        LocalDate selected = date.getValue();
        if (original != null) {
            hours.setText(BigDecimal.valueOf(original.seconds()).divide(BigDecimal.valueOf(3600), 6, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString());
            note.setText(String.join("\n", original.notes()));
            estimated.setSelected(original.estimatedSeconds() > 0);
        }
        VBox taskField = field("事项", original == null ? task : label(original.task().title(), "section-title", "wrap"));
        VBox durationField = new VBox(5, field(original == null ? "补录工时（小时）" : "当天总工时（小时）", hours),
                label(DAY.format(selected) + " · 按全天上班时段排时，跳过午休和已占用时间。", "muted", "wrap"));
        VBox fields = new VBox(12, taskField, durationField, field("备注或结果", note), estimated);
        Dialog<ButtonType> dialog = form(stage, original == null ? "补录工时" : "编辑工时", fields, "保存");
        bindSave(dialog, () -> {
            long seconds = parseHours(hours.getText());
            if (original != null) {
                timer.editWorkHours(selected, original, seconds, note.getText(), estimated.isSelected());
            } else {
                String title = task.getEditor().getText().strip();
                WorkTask chosen = choices.get(title);
                timer.addWorkHours(selected, chosen == null ? null : chosen.id(), title, seconds,
                        note.getText(), estimated.isSelected());
            }
        });
        if (dialog.showAndWait().filter(dialog.getDialogPane().getButtonTypes().getFirst()::equals).isPresent()) {
            app.refresh();
        }
    }

    private static long parseHours(String text) {
        String input = text.strip();
        if (!input.matches("(?:[0-9]{1,2}(?:\\.[0-9]{1,6})?|\\.[0-9]{1,6})")) {
            throw new IllegalArgumentException("请输入有效工时，例如 3 或 1.5 小时。");
        }
        return new BigDecimal(input).multiply(BigDecimal.valueOf(3600))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private void editWorkSchedule() throws SQLException {
        WorkSchedule schedule = timer.workSchedule();
        TextField morningStart = new TextField(clockTime(schedule.morningStart()));
        TextField morningEnd = new TextField(clockTime(schedule.morningEnd()));
        TextField afternoonStart = new TextField(clockTime(schedule.afternoonStart()));
        TextField afternoonEnd = new TextField(clockTime(schedule.afternoonEnd()));
        VBox fields = new VBox(12,
                new HBox(10, field("上午上班", morningStart), field("上午下班", morningEnd)),
                new HBox(10, field("下午上班", afternoonStart), field("下午下班", afternoonEnd)),
                label("补录或编辑工时时，按这里设置的时段排时。", "muted", "wrap"));
        Dialog<ButtonType> dialog = form(stage, "上班时段", fields, "保存");
        bindSave(dialog, () -> timer.saveWorkSchedule(new WorkSchedule(parseWorkTime(morningStart.getText()),
                parseWorkTime(morningEnd.getText()), parseWorkTime(afternoonStart.getText()),
                parseWorkTime(afternoonEnd.getText()))));
        dialog.showAndWait();
    }

    private static String clockTime(int minutes) {
        return LocalTime.ofSecondOfDay(minutes * 60L).toString();
    }

    private static int parseWorkTime(String text) {
        try {
            return LocalTime.parse(text.strip().replace('.', ':'), WORK_TIME).toSecondOfDay() / 60;
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("请输入有效时刻，例如 09:20 或 13:50。");
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
        if (binding != null) {
            return "云效 · " + binding.label();
        }
        return cloudPending.contains(task.id()) ? "云效 · 子任务待创建" : task.workItem();
    }

    private static <T> void configureList(ListView<T> list, Function<T, Region> createCard) {
        list.getStyleClass().add("review-records");
        list.setMinHeight(0);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    Region card = createCard.apply(item);
                    card.prefWidthProperty().bind(Bindings.max(0, widthProperty().subtract(2)));
                    card.pseudoClassStateChanged(LAST_RECORD, getIndex() == view.getItems().size() - 1);
                    setGraphic(card);
                }
            }
        });
    }

    private static VBox emptyRecords() {
        VBox empty = new VBox(5, label("当天暂无记录", "section-title"),
                label("完成计时或补录工时后，记录会显示在这里。", "muted", "wrap"));
        empty.setAlignment(Pos.CENTER);
        empty.getStyleClass().add("review-empty");
        return empty;
    }
}
