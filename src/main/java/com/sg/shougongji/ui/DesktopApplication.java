package com.sg.shougongji.ui;

import com.sg.shougongji.AppServices;
import com.sg.shougongji.model.DayReview;
import com.sg.shougongji.model.WorkSession;
import com.sg.shougongji.model.WorkTask;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.model.Yunxiao.PendingEffort;
import com.sg.shougongji.service.ReviewService;
import com.sg.shougongji.service.TimerService;
import com.sg.shougongji.service.YunxiaoAutoSync;
import com.sg.shougongji.service.YunxiaoService;
import com.sg.shougongji.service.YunxiaoSettingsService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.sg.shougongji.ui.UiSupport.appScene;
import static com.sg.shougongji.ui.UiSupport.background;
import static com.sg.shougongji.ui.UiSupport.bindSave;
import static com.sg.shougongji.ui.UiSupport.button;
import static com.sg.shougongji.ui.UiSupport.clockDisplay;
import static com.sg.shougongji.ui.UiSupport.field;
import static com.sg.shougongji.ui.UiSupport.form;
import static com.sg.shougongji.ui.UiSupport.formatTime;
import static com.sg.shougongji.ui.UiSupport.icon;
import static com.sg.shougongji.ui.UiSupport.label;
import static com.sg.shougongji.ui.UiSupport.parseTime;
import static com.sg.shougongji.ui.UiSupport.perform;
import static com.sg.shougongji.ui.UiSupport.showError;
import static com.sg.shougongji.ui.UiSupport.spacer;
import static com.sg.shougongji.ui.UiSupport.styleDialog;

public final class DesktopApplication extends Application {
    private static final System.Logger LOGGER = System.getLogger(DesktopApplication.class.getName());
    private static final boolean WINDOWS = com.sun.jna.Platform.isWindows();
    private static final KeyCombination.Modifier SHORTCUT_MODIFIER =
            WINDOWS ? KeyCombination.ALT_DOWN : KeyCombination.SHIFT_DOWN;
    private static final String SHORTCUT_PREFIX = WINDOWS ? "Ctrl + Alt + " : "Ctrl + Shift + ";
    private static final String CLOUD_START_LABEL = "从云效开始…";
    private static final KeyCodeCombination QUICK_INPUT = new KeyCodeCombination(
            KeyCode.SPACE, KeyCombination.CONTROL_DOWN, SHORTCUT_MODIFIER);
    private static final KeyCodeCombination QUICK_PAUSE = new KeyCodeCombination(
            KeyCode.P, KeyCombination.CONTROL_DOWN, SHORTCUT_MODIFIER);
    private static final PseudoClass COMPACT = PseudoClass.getPseudoClass("compact");
    private static final PseudoClass RUNNING = PseudoClass.getPseudoClass("running");
    private static final PseudoClass LONG_TIME = PseudoClass.getPseudoClass("long-time");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.SIMPLIFIED_CHINESE);

    private final Clock clock = Clock.systemDefaultZone();
    private final Label state = label("", "timer-status");
    private final Label activeTitle = label("", "task-title");
    private final Label elapsed = label("", "timer");
    private final Label date = label("", "timer-date");
    private final TimerDial dial = new TimerDial();
    private final TextField taskInput = new TextField();
    private final VBox recent = new VBox();
    private final Button start = button("开始计时", "primary");
    private final Button pause = button("暂停", "secondary");
    private final Button cloudSync = button("云效待同步", "subtle");
    private final MenuItem shortcutScope = new MenuItem("在收工记窗口内可用");

    private FileChannel lockChannel;
    private FileLock lock;
    private TimerService timer;
    private ReviewService reviewService;
    private WorkSession active;
    private LocalDate timerDate;
    private long displayedSeconds = -1;
    private Task<RecentSnapshot> recentLoad;
    private long recentRevision;
    private ReviewWindow review;
    private YunxiaoDialogs yunxiao;
    private YunxiaoSettingsService yunxiaoSettings;
    private YunxiaoAutoSync autoSync;
    private Task<YunxiaoAutoSync.Failure> autoSyncTask;
    private YunxiaoAutoSync.Failure autoSyncFailure;
    private Throwable autoSyncError;
    private boolean autoSyncAgain;
    private Timeline tick;
    private Stage timerStage;
    private MiniTimerWindow miniTimer;
    private TimerHotkeys systemHotkeys;
    private QuickEntry quickEntry;

    @Override
    public void start(Stage stage) {
        timerStage = stage;
        try {
            Path dataDirectory = Path.of(System.getProperty("shougongji.dataDir",
                    Path.of(System.getProperty("user.home"), ".shougongji").toString()));
            Files.createDirectories(dataDirectory);
            lockChannel = FileChannel.open(dataDirectory.resolve("app.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) {
                throw new IOException("收工记已打开，请返回已有的计时窗口。");
            }
            AppServices services = AppServices.open(dataDirectory.resolve("worklog.sqlite"), clock);
            timer = services.timer();
            reviewService = services.review();
            yunxiaoSettings = services.yunxiaoSettings();
            autoSync = services.yunxiaoAutoSync();
            yunxiao = new YunxiaoDialogs(yunxiaoSettings, getHostServices()::showDocument);
            refresh();
        } catch (IOException | SQLException failure) {
            showError(null, failure instanceof SQLException
                    ? "无法打开本地记录库。请检查数据目录和磁盘空间。" : failure.getMessage());
            Platform.exit();
            return;
        }

        stage.setTitle("收工记");
        VBox content = timerContent();
        Scene scene = appScene(content, 394, -1);
        ChangeListener<Number> resize = (property, previous, value) -> content.pseudoClassStateChanged(COMPACT,
                scene.getWidth() < 355 || scene.getHeight() > 0 && scene.getHeight() < 455);
        scene.widthProperty().addListener(resize);
        scene.heightProperty().addListener(resize);
        stage.setScene(scene);
        stage.setMinWidth(320);
        stage.setMinHeight(455);
        stage.setAlwaysOnTop(true);
        stage.iconifiedProperty().addListener((property, previous, minimized) -> updateMiniTimer());
        stage.showingProperty().addListener((property, previous, showing) -> updateMiniTimer());
        stage.setOnCloseRequest(event -> {
            if (active != null && !confirmExit()) {
                event.consume();
                return;
            }
            Platform.exit();
        });
        stage.show();
        taskInput.requestFocus();
        registerShortcuts(scene);

        tick = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            LocalDate today = LocalDate.now(clock);
            if (!today.equals(timerDate)) {
                timerDate = today;
                perform(stage, this::refresh);
            }
            updateClock();
        }));
        tick.setCycleCount(Timeline.INDEFINITE);
        tick.play();
        Platform.runLater(() -> {
            if (active == null || recover()) {
                perform(stage, () -> {
                    requestAutoSync();
                    yunxiao.offerDefaultAssignee(stage);
                });
            }
        });
    }

    @Override
    public void stop() throws IOException {
        if (tick != null) {
            tick.stop();
        }
        if (miniTimer != null) {
            miniTimer.hide();
        }
        if (autoSyncTask != null) {
            autoSyncTask.cancel();
        }
        if (recentLoad != null) {
            recentLoad.cancel();
        }
        try {
            if (systemHotkeys != null) {
                systemHotkeys.close();
            }
        } finally {
            if (lockChannel != null) {
                lockChannel.close();
            }
        }
    }

    private void registerShortcuts(Scene scene) {
        addWindowShortcuts(scene);
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            if (WINDOWS) {
                systemHotkeys = WindowsTimerHotkeys.register(QUICK_INPUT.getCode().getCode(),
                        QUICK_PAUSE.getCode().getCode(), () -> Platform.runLater(() -> openQuickEntry(true)),
                        () -> Platform.runLater(this::pauseTimer));
                shortcutScope.setText("Windows 全局快捷键已启用");
            } else if (com.sun.jna.Platform.isMac()) {
                systemHotkeys = MacTimerHotkeys.register(this::quickEntryMacHotkey,
                        () -> Platform.runLater(this::pauseTimer));
                shortcutScope.setText("macOS 全局快捷键已启用");
            }
        } catch (RuntimeException | LinkageError failure) {
            shortcutScope.setText("全局键注册失败 · 窗口内可用");
            LOGGER.log(System.Logger.Level.WARNING, "Could not register system timer shortcuts", failure);
        }
    }

    private void quickEntryMacHotkey() {
        if (modalIsOpen()) {
            return;
        }
        // Request activation while AppKit is still handling the user's global shortcut.
        try {
            MacTimerHotkeys.activateApplication();
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not activate the quick timer window", failure);
        }
        Platform.runLater(this::openQuickEntry);
    }

    void addWindowShortcuts(Scene scene) {
        scene.getAccelerators().put(QUICK_INPUT, this::openQuickEntry);
        scene.getAccelerators().put(QUICK_PAUSE, this::pauseTimer);
    }

    private boolean modalIsOpen() {
        return review != null && review.isEffortOpen() || Window.getWindows().stream().anyMatch(window ->
                window instanceof Stage stage && stage.getModality() != Modality.NONE);
    }

    private void openQuickEntry() {
        openQuickEntry(false);
    }

    private void openQuickEntry(boolean activateWindows) {
        if (modalIsOpen()) {
            return;
        }
        if (quickEntry == null) {
            quickEntry = new QuickEntry();
        }
        quickEntry.show(activateWindows);
    }

    private void pauseTimer() {
        if (active != null && !modalIsOpen()) {
            Stage owner = quickEntry == null ? timerStage : quickEntry.window();
            perform(owner, () -> {
                timer.pause(active.id(), clock.instant(), false);
                refresh();
                requestAutoSync();
            });
        }
    }

    private final class QuickEntry {
        private final TextField input = new TextField();
        private final Dialog<ButtonType> dialog;

        QuickEntry() {
            input.setPromptText("输入事项名称");
            input.setAccessibleText("快捷计时事项");
            Label current = label("", "muted");
            current.setMaxWidth(320);
            current.textProperty().bind(Bindings.concat("正在计时 · ", activeTitle.textProperty()));
            current.visibleProperty().bind(activeTitle.textProperty().isNotEmpty());
            current.managedProperty().bind(current.visibleProperty());
            Button cloud = button(CLOUD_START_LABEL, "subtle");
            HBox shortcuts = new HBox(8, label("回车确认，Esc 取消", "muted"), spacer(), cloud);
            shortcuts.setAlignment(Pos.CENTER_LEFT);
            VBox fields = new VBox(9, current, field("事项", input), shortcuts);
            dialog = form(null, "快捷计时", fields, "开始计时");
            dialog.initModality(Modality.NONE);
            dialog.getDialogPane().getStyleClass().add("quick-timer");
            dialog.getDialogPane().setPrefWidth(360);
            ObservableList<ButtonType> types = dialog.getDialogPane().getButtonTypes();
            types.set(1, new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE));
            Button confirm = (Button) dialog.getDialogPane().lookupButton(types.getFirst());
            confirm.textProperty().bind(start.textProperty());
            confirm.disableProperty().bind(Bindings.createBooleanBinding(
                    () -> input.getText().isBlank(), input.textProperty()));
            input.setOnAction(event -> confirm.fire());
            bindSave(dialog, () -> startTask(input.getText()));
            cloud.setOnAction(event -> perform(window(), () -> {
                String title = input.getText().isBlank() ? taskInput.getText() : input.getText();
                startCloudTask(window(), title, started -> {
                    if (started) {
                        dialog.close();
                    } else {
                        input.requestFocus();
                    }
                });
            }));
            dialog.setOnHidden(event -> quickEntry = null);
        }

        Stage window() {
            return (Stage) dialog.getDialogPane().getScene().getWindow();
        }

        void show(boolean activateWindows) {
            if (!dialog.isShowing()) {
                dialog.show();
                window().setAlwaysOnTop(true);
                addWindowShortcuts(window().getScene());
            }
            Stage window = window();
            window.toFront();
            if (activateWindows) {
                try {
                    WindowsTimerHotkeys.activateWindow(window.getTitle());
                } catch (RuntimeException | LinkageError failure) {
                    LOGGER.log(System.Logger.Level.WARNING, "Could not activate the quick timer window", failure);
                }
            }
            window.requestFocus();
            input.requestFocus();
        }
    }

    private VBox timerContent() {
        VBox root = new VBox(17, createTimerHeader(), createTimerReadout(), createTimerControls(), createRecentSection());
        root.setAlignment(Pos.TOP_CENTER);
        root.getStyleClass().add("timer-page");
        return root;
    }

    private HBox createTimerHeader() {
        Button openReview = button("收工页", "toolbar-button");
        openReview.getStyleClass().add("timer-review");
        openReview.setGraphic(icon("M5 2.5 L9 6.5 L5 10.5"));
        openReview.setContentDisplay(ContentDisplay.RIGHT);
        openReview.setGraphicTextGap(5);
        openReview.setOnAction(event -> perform(timerStage, () -> {
            if (review == null) {
                review = new ReviewWindow(this, timer, reviewService, clock, timerStage, yunxiao);
            }
            review.show();
        }));
        MenuItem quickInput = new MenuItem("开始或切换   " + SHORTCUT_PREFIX + "空格");
        quickInput.setOnAction(event -> openQuickEntry());
        MenuItem quickPause = new MenuItem("暂停   " + SHORTCUT_PREFIX + "P");
        quickPause.disableProperty().bind(pause.disableProperty());
        quickPause.setOnAction(event -> pauseTimer());
        shortcutScope.setDisable(true);
        MenuButton shortcuts = new MenuButton("快捷键", null, quickInput, quickPause, new SeparatorMenuItem(), shortcutScope);
        shortcuts.getStyleClass().add("timer-shortcuts");
        shortcuts.setMinWidth(Region.USE_PREF_SIZE);
        VBox heading = new VBox(3, label("计时", "timer-heading"), date);
        HBox header = new HBox(8, heading, spacer(), shortcuts, openReview);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Group createTimerReadout() {
        Region dot = new Region();
        dot.getStyleClass().add("status-dot");
        state.setGraphic(dot);
        state.setGraphicTextGap(7);
        elapsed.setMinWidth(0);
        elapsed.setMaxWidth(176);
        elapsed.setAlignment(Pos.CENTER);
        elapsed.setAccessibleHelp("本段已计时，依次显示小时、分钟和秒");
        Tooltip elapsedTooltip = new Tooltip();
        elapsedTooltip.textProperty().bind(elapsed.textProperty());
        elapsed.setTooltip(elapsedTooltip);
        activeTitle.setMinWidth(0);
        activeTitle.setMaxWidth(144);
        activeTitle.setAlignment(Pos.CENTER);
        Tooltip activeTaskTooltip = new Tooltip();
        activeTaskTooltip.textProperty().bind(activeTitle.textProperty());
        activeTitle.setTooltip(activeTaskTooltip);
        VBox readout = new VBox(4, state, elapsed, activeTitle);
        readout.setAlignment(Pos.CENTER);
        dial.getChildren().add(readout);
        return new Group(dial);
    }

    private VBox createTimerControls() {
        pause.getStyleClass().add("timer-pause");
        pause.setGraphic(icon("M3.5 2.5 V9.5 M8 2.5 V9.5"));
        pause.setMinWidth(Region.USE_PREF_SIZE);
        pause.visibleProperty().bind(pause.disableProperty().not());
        pause.managedProperty().bind(pause.visibleProperty());
        taskInput.setAccessibleText("工作事项");
        taskInput.setMinWidth(0);
        taskInput.setOnAction(event -> start.fire());
        start.setGraphic(icon("M3.5 2.5 L9 6 L3.5 9.5 Z"));
        start.getStyleClass().add("timer-start");
        start.setMinWidth(Region.USE_PREF_SIZE);
        start.setOnAction(event -> begin(taskInput.getText()));
        pause.setOnAction(event -> pauseTimer());
        HBox composer = new HBox(7, taskInput, start, pause);
        composer.setAlignment(Pos.CENTER_LEFT);
        composer.getStyleClass().add("timer-composer");
        HBox.setHgrow(taskInput, Priority.ALWAYS);
        Button cloud = button(CLOUD_START_LABEL, "subtle");
        cloud.setOnAction(event -> perform(timerStage,
                () -> startCloudTask(timerStage, taskInput.getText(), ignored -> {})));
        cloudSync.managedProperty().bind(cloudSync.visibleProperty());
        cloudSync.setOnAction(event -> perform(timerStage, () -> retryAutoSync(timerStage)));
        HBox cloudActions = new HBox(4, cloud, spacer(), cloudSync);
        cloudActions.setAlignment(Pos.CENTER_LEFT);
        return new VBox(3, composer, cloudActions);
    }

    private VBox createRecentSection() {
        HBox recentHeading = new HBox(8, label("最近事项", "section-title"), spacer(), label("今日累计", "muted"));
        recentHeading.setAlignment(Pos.CENTER_LEFT);
        recentHeading.getStyleClass().add("recent-heading");
        ScrollPane recentScroll = new ScrollPane(recent);
        recentScroll.getStyleClass().add("timer-recent-list");
        recentScroll.setFitToWidth(true);
        recentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(recentScroll, Priority.ALWAYS);
        VBox recentSection = new VBox(5, recentHeading, recentScroll);
        recentSection.getStyleClass().add("timer-recents");
        VBox.setVgrow(recentSection, Priority.ALWAYS);
        return recentSection;
    }

    void refresh() throws SQLException {
        LocalDate today = LocalDate.now(clock);
        active = timer.active();
        timerDate = today;
        date.setText(today.format(SHORT_DATE));
        boolean isRunning = active != null;
        state.setText(isRunning ? "计时中" : "未在计时");
        state.pseudoClassStateChanged(RUNNING, isRunning);
        activeTitle.setText(isRunning ? active.task().title() : "");
        start.setText(isRunning ? "切换事项" : "开始计时");
        start.pseudoClassStateChanged(RUNNING, isRunning);
        taskInput.setPromptText(isRunning ? "输入另一件事项" : "输入事项名称");
        pause.setDisable(!isRunning);
        updateClock();
        updateMiniTimer();
        loadRecentTasks(today);
        refreshReview();
        updateAutoSyncStatus();
    }

    private void loadRecentTasks(LocalDate day) {
        if (recentLoad != null) {
            recentLoad.cancel();
        }
        long revision = ++recentRevision;
        recentLoad = background(() -> {
            ReviewService.Overview overview = reviewService.loadOverview(day);
            Map<Long, Long> seconds = overview.review().entries().stream()
                    .collect(Collectors.toMap(entry -> entry.task().id(), DayReview.Entry::seconds));
            return new RecentSnapshot(timer.recentTasks(), seconds, overview.bindings());
        }, snapshot -> {
            if (revision == recentRevision) {
                recentLoad = null;
                showRecentTasks(snapshot.tasks(), snapshot.seconds(), snapshot.bindings());
            }
        }, failure -> {
            if (revision == recentRevision) {
                recentLoad = null;
                recent.getChildren().setAll(label("无法读取最近事项，请稍后重试。", "timer-empty", "wrap"));
            }
        });
    }

    private record RecentSnapshot(List<WorkTask> tasks, Map<Long, Long> seconds, Map<Long, Binding> bindings) {}

    private void showRecentTasks(List<WorkTask> recentTasks, Map<Long, Long> todaySecondsByTask,
                                 Map<Long, Binding> bindings) {
        recent.getChildren().clear();
        List<WorkTask> visibleTasks = recentTasks.stream()
                .filter(task -> active == null || task.id() != active.task().id())
                .limit(4)
                .toList();
        Map<String, Long> titleCounts = visibleTasks.stream()
                .collect(Collectors.groupingBy(WorkTask::title, Collectors.counting()));
        for (WorkTask task : visibleTasks) {
            long todaySeconds = todaySecondsByTask.getOrDefault(task.id(), 0L);
            recent.getChildren().add(createRecentTaskRow(task, todaySeconds, bindings.get(task.id()),
                    titleCounts.get(task.title()) > 1));
        }
        if (recent.getChildren().isEmpty()) {
            Label empty = label("计时结束后，可从这里再次开始该事项。", "timer-empty", "wrap");
            recent.getChildren().add(empty);
        }
    }

    private HBox createRecentTaskRow(WorkTask task, long todaySeconds, Binding binding, boolean repeatedTitle) {
        String duration = DayReview.duration(todaySeconds);
        Label total = label(duration, "recent-total");
        total.setMinWidth(Region.USE_PREF_SIZE);

        String context = binding == null ? "本地事项" : binding.label();
        String title = repeatedTitle ? task.title() + " · " + context : task.title();
        Button startTask = button(title, "recent");
        StackPane playIcon = new StackPane(icon("M3.5 2.5 L9 6 L3.5 9.5 Z"));
        playIcon.getStyleClass().add("recent-play");
        startTask.setGraphic(playIcon);
        startTask.setGraphicTextGap(9);
        startTask.setAccessibleText(title + "，今日累计 " + duration);
        startTask.setTooltip(new Tooltip(binding == null ? task.title() : task.title() + "\n" + context));
        startTask.setMinWidth(0);
        startTask.setMaxWidth(Double.MAX_VALUE);
        startTask.setOnAction(event -> perform(timerStage, () -> {
            timer.start(task.id());
            taskStarted();
        }));
        HBox.setHgrow(startTask, Priority.ALWAYS);

        MenuItem removeFromRecent = new MenuItem("从最近事项移除");
        removeFromRecent.setOnAction(event -> perform(timerStage, () -> {
            timer.hideFromRecent(task.id());
            refresh();
        }));
        MenuItem deleteTask = new MenuItem("删除事项及计时记录…");
        deleteTask.setOnAction(event -> confirmDeleteTask(timerStage, task));
        MenuButton moreActions = new MenuButton("⋯", null, removeFromRecent, deleteTask);
        moreActions.getStyleClass().add("recent-menu");
        moreActions.setAccessibleText(task.title() + " 的更多操作");
        moreActions.setTooltip(new Tooltip("更多操作"));
        moreActions.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(6, startTask, total, moreActions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("recent-row");
        return row;
    }

    void confirmDeleteTask(Window owner, WorkTask task) {
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType delete = new ButtonType("永久删除", ButtonBar.ButtonData.OTHER);
        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION,
                "该事项在所有日期的本地计时记录、备注和工作项关联都会删除。此操作无法撤销。"
                        + "云效上的子任务和已提交工时会保留。", cancel, delete);
        prompt.initOwner(owner);
        prompt.setTitle("删除事项");
        prompt.setHeaderText("删除「" + task.title() + "」？");
        styleDialog(prompt, 440);
        Button cancelButton = (Button) prompt.getDialogPane().lookupButton(cancel);
        cancelButton.setDefaultButton(true);
        prompt.setOnShown(event -> cancelButton.requestFocus());
        prompt.getDialogPane().lookupButton(delete).getStyleClass().add("danger");
        if (prompt.showAndWait().filter(delete::equals).isPresent()) {
            perform(owner, () -> {
                if (autoSyncTask != null && autoSync.isAutomaticTask(task.id())) {
                    throw new IllegalArgumentException("云效正在同步，请在同步结束后删除该事项。");
                }
                timer.deleteTask(task.id());
                refresh();
            });
        }
    }

    private void begin(String title) {
        perform(timerStage, () -> startTask(title));
    }

    private void startTask(String title) throws SQLException {
        timer.start(title);
        taskStarted();
    }

    private void startCloudTask(Window owner, String title, Consumer<Boolean> onClosed) throws SQLException {
        yunxiao.startTimer(owner, timer, LocalDate.now(clock), title, started -> {
            if (started) {
                perform(owner, this::taskStarted);
            }
            onClosed.accept(started);
        });
    }

    private void taskStarted() throws SQLException {
        taskInput.clear();
        refresh();
        requestAutoSync();
    }

    void requestAutoSync() throws SQLException {
        requestAutoSync(null);
    }

    private void requestAutoSync(PendingEffort confirmedRetry) throws SQLException {
        if (autoSyncTask != null) {
            autoSyncAgain = true;
            return;
        }
        if (autoSync.pendingCount() == 0) {
            return;
        }
        YunxiaoService connection = yunxiaoSettings.currentConnection();
        if (connection == null) {
            updateAutoSyncStatus();
            return;
        }
        Task<YunxiaoAutoSync.Failure> sync = new Task<>() {
            @Override
            protected YunxiaoAutoSync.Failure call() throws SQLException, InterruptedException {
                return autoSync.sync(connection, confirmedRetry);
            }
        };
        sync.setOnSucceeded(event -> finishAutoSync(sync.getValue(), null));
        sync.setOnFailed(event -> finishAutoSync(null, sync.getException()));
        autoSyncTask = sync;
        Thread.startVirtualThread(sync);
        updateAutoSyncStatus();
    }

    private void finishAutoSync(YunxiaoAutoSync.Failure failure, Throwable error) {
        autoSyncTask = null;
        autoSyncFailure = failure;
        autoSyncError = failure == null ? error : failure.cause();
        boolean again = autoSyncAgain;
        autoSyncAgain = false;
        perform(timerStage, () -> {
            refresh();
            if (again) {
                requestAutoSync();
            }
        });
    }

    private void updateAutoSyncStatus() throws SQLException {
        int pending = autoSync.pendingCount();
        boolean syncing = autoSyncTask != null;
        cloudSync.setVisible(syncing || pending > 0);
        cloudSync.setDisable(syncing);
        cloudSync.setText(syncing ? "云效同步中…" : "待同步 " + pending + " · 重试");
        if (pending == 0) {
            autoSyncFailure = null;
            autoSyncError = null;
        }
        String detail = autoSyncError == null ? "待同步记录会保存在本机。"
                : YunxiaoDialogs.errorText(autoSyncError);
        if (autoSyncError instanceof YunxiaoService.ManualEffortRequired) {
            detail += " 可在收工页的「云效工时」中处理。";
        }
        cloudSync.setTooltip(new Tooltip(detail));
    }

    void retryAutoSync(Window owner) throws SQLException {
        if (autoSyncTask != null || autoSync.pendingCount() == 0) {
            return;
        }
        if (yunxiaoSettings.currentConnection() == null) {
            yunxiao.configure(owner);
        }
        PendingEffort confirmedRetry = null;
        if (autoSyncFailure != null && autoSyncFailure.cause() instanceof YunxiaoService.UncertainWrite) {
            PendingEffort effort = autoSyncFailure.effort();
            ButtonType confirm = new ButtonType("已核对，重试", ButtonBar.ButtonData.OK_DONE);
            Alert prompt = new Alert(Alert.AlertType.CONFIRMATION,
                    "请在云效核对「" + effort.title() + "」的子任务和 " + effort.day()
                            + " 的工时。确认上次写入未成功后再重试。", confirm, ButtonType.CANCEL);
            prompt.initOwner(owner);
            prompt.setTitle("核对云效写入");
            prompt.setHeaderText("上次云效写入结果待确认");
            styleDialog(prompt, 440);
            if (prompt.showAndWait().orElse(ButtonType.CANCEL) != confirm) {
                return;
            }
            confirmedRetry = effort;
        }
        requestAutoSync(confirmedRetry);
    }

    private void refreshReview() {
        if (review != null && review.isShowing()) {
            review.refresh();
        }
    }

    private void updateClock() {
        long seconds = active == null ? 0 : Math.max(0, timer.now() - active.start());
        if (displayedSeconds != seconds) {
            displayedSeconds = seconds;
            String display = clockDisplay(seconds);
            elapsed.pseudoClassStateChanged(LONG_TIME, display.length() > 8);
            elapsed.setText(display);
        }
        if (!timerStage.isIconified()) {
            dial.update(seconds, active != null);
        }
    }

    private void updateMiniTimer() {
        if (active != null && timerStage.isShowing() && timerStage.isIconified()) {
            if (miniTimer == null) {
                miniTimer = new MiniTimerWindow(timerStage, activeTitle.textProperty(), elapsed.textProperty());
                addWindowShortcuts(miniTimer.getScene());
            }
            if (!miniTimer.isShowing()) {
                miniTimer.show();
            }
        } else if (miniTimer != null) {
            miniTimer.hide();
        }
    }

    private boolean confirmExit() {
        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION);
        prompt.initOwner(timerStage);
        prompt.setTitle("退出收工记");
        prompt.setHeaderText("「" + active.task().title() + "」仍在计时");
        prompt.setContentText("现在结束这段计时并退出，或返回计时窗口。");
        ButtonType stopAndExit = new ButtonType("结束并退出");
        prompt.getButtonTypes().setAll(stopAndExit, ButtonType.CANCEL);
        styleDialog(prompt, 440);
        if (prompt.showAndWait().orElse(ButtonType.CANCEL) != stopAndExit) {
            return false;
        }
        try {
            timer.pause(active.id(), clock.instant(), false);
            return true;
        } catch (SQLException | IllegalArgumentException failure) {
            showError(timerStage, "未能保存结束时间，计时记录仍保留在本地。");
            return false;
        }
    }

    private boolean recover() {
        WorkSession pending = active;
        String from = formatTime(pending.start(), clock.getZone());
        TextField end = new TextField();
        end.setPromptText("例如 " + formatTime(timer.now(), clock.getZone()));
        CheckBox estimated = new CheckBox("这个结束时间是估算");
        estimated.setSelected(true);
        ButtonType resume = new ButtonType("持续工作，继续计时");
        ButtonType quit = new ButtonType("稍后核对并退出", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        Dialog<ButtonType> dialog = form(timerStage, "核对上次计时",
                new VBox(10, label("「" + pending.task().title() + "」从 " + from + " 开始。", "wrap"),
                        label("如果中途停过，请填实际结束时间。继续计时会计入离线时段。", "muted", "wrap"),
                        end, estimated), "确认结束时间");
        dialog.getDialogPane().getButtonTypes().setAll(dialog.getDialogPane().getButtonTypes().getFirst(), resume, quit);
        bindSave(dialog, () -> {
            timer.pause(pending.id(), parseTime(end.getText(), clock.getZone(), null), estimated.isSelected());
            refresh();
        });
        ButtonType choice = dialog.showAndWait().orElse(quit);
        if (choice == quit) {
            Platform.exit();
            return false;
        }
        return true;
    }
}
