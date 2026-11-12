package com.sg.shougongji.ui;

import com.sg.shougongji.model.DayReview;
import com.sg.shougongji.model.Yunxiao.ApiException;
import com.sg.shougongji.model.Yunxiao.Assignee;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.model.Yunxiao.ChildDraft;
import com.sg.shougongji.model.Yunxiao.Choice;
import com.sg.shougongji.model.Yunxiao.ConnectionSettings;
import com.sg.shougongji.model.Yunxiao.Field;
import com.sg.shougongji.model.Yunxiao.Item;
import com.sg.shougongji.model.Yunxiao.Page;
import com.sg.shougongji.model.Yunxiao.ProtocolException;
import com.sg.shougongji.model.Yunxiao.WorkItemKind;
import com.sg.shougongji.service.TimerService;
import com.sg.shougongji.service.YunxiaoService;
import com.sg.shougongji.service.YunxiaoSettingsService;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.sg.shougongji.ui.UiSupport.background;
import static com.sg.shougongji.ui.UiSupport.bindSave;
import static com.sg.shougongji.ui.UiSupport.button;
import static com.sg.shougongji.ui.UiSupport.field;
import static com.sg.shougongji.ui.UiSupport.form;
import static com.sg.shougongji.ui.UiSupport.label;
import static com.sg.shougongji.ui.UiSupport.spacer;
import static com.sg.shougongji.ui.UiSupport.styleDialog;

final class YunxiaoDialogs {
    private static final List<String> TITLE_ACTIONS = List.of("排查", "修复", "联调", "验证");

    private final YunxiaoSettingsService settings;
    private final Consumer<String> openPage;

    YunxiaoDialogs(YunxiaoSettingsService settings, Consumer<String> openPage) {
        this.settings = settings;
        this.openPage = openPage;
    }

    void configure(Window owner) throws SQLException {
        ConnectionSettings saved = settings.readSettings();
        YunxiaoService savedConnection = settings.openSavedConnection(saved);
        TextField org = new TextField(saved == null ? "" : saved.organizationId());
        org.setPromptText("中心版填写组织 ID");
        TextField endpoint = new TextField(saved == null ? "" : saved.instance());
        endpoint.setPromptText("Region 版填写 HTTPS 实例域名");
        PasswordField token = new PasswordField();
        token.setPromptText(saved == null ? "云效个人访问令牌" : "已记住，留空沿用");
        VBox fields = new VBox(10, field("组织 ID", org), field("Region 版接入地址", endpoint),
                field("个人访问令牌", token),
                label("中心版填写组织 ID，接入地址留空。Region 版填写接入地址，组织 ID 留空。"
                        + "连接信息保存在本机。更换组织或实例时，请重新填写令牌。", "muted", "wrap"));
        Dialog<ButtonType> dialog = form(owner, "云效连接设置", fields, "使用此连接");
        Button help = button("帮助", "subtle");
        HBox header = new HBox(8, label(dialog.getTitle()), spacer(), help);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header-panel");
        dialog.getDialogPane().setHeader(header);
        help.setOnAction(event -> YunxiaoHelp.show(dialog.getDialogPane().getScene().getWindow(), openPage));
        bindSave(dialog, () -> settings.saveConnection(endpoint.getText(), org.getText(), token.getText(), saved));
        ButtonType forget = new ButtonType("清除连接记忆", ButtonBar.ButtonData.LEFT);
        ButtonType assigneeSettings = new ButtonType("默认负责人…", ButtonBar.ButtonData.OTHER);
        if (saved != null) {
            dialog.getDialogPane().getButtonTypes().add(forget);
        }
        if (savedConnection != null) {
            dialog.getDialogPane().getButtonTypes().add(assigneeSettings);
        }
        ButtonType choice;
        try {
            choice = dialog.showAndWait().orElse(ButtonType.CANCEL);
        } finally {
            token.clear();
        }
        if (choice == forget) {
            settings.forgetConnection();
        } else if (choice == assigneeSettings) {
            new AssigneeSettings(owner, savedConnection, savedConnection.defaultAssignee()).show();
        } else if (choice.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
            offerDefaultAssignee(owner);
        }
    }

    void offerDefaultAssignee(Window owner) throws SQLException {
        YunxiaoService connection = settings.currentConnection();
        if (connection != null && connection.defaultAssignee() == null) {
            new AssigneeSettings(owner, connection, null).show();
        }
    }

    void associate(Window owner, DayReview.Entry entry, LocalDate day, Consumer<Binding> onBound) throws SQLException {
        showBindingDialog(owner, entry, day, false, onBound);
    }

    EffortOverlay effortOverlay(Window owner, DayReview.Entry entry, LocalDate day, Binding binding,
                                Consumer<EffortOverlay> onClose, Runnable onChanged) throws SQLException {
        YunxiaoService connection = connection(owner);
        if (connection == null) {
            return null;
        }
        if (!binding.scope().equals(connection.scope())) {
            throw new IllegalArgumentException("该事项已绑定到其他云效组织或实例，请在「云效设置」中切换连接。");
        }
        return new WorkDialog(connection, entry, day, null).effortOverlay(binding, onClose, onChanged);
    }

    void editStatus(Window owner, Binding binding) throws SQLException {
        YunxiaoService connection = connection(owner);
        if (connection != null) {
            new StatusDialog(owner, connection, binding).show();
        }
    }

    void showWeek(Window owner, LocalDate today) throws SQLException {
        YunxiaoService connection = connection(owner);
        if (connection != null) {
            new YunxiaoWeekDialog(owner, connection, today).show();
        }
    }

    void create(Window owner, DayReview.Entry entry, LocalDate day, Consumer<Binding> onBound) throws SQLException {
        showBindingDialog(owner, entry, day, true, onBound);
    }

    void linkAutomaticChild(Window owner, DayReview.Entry entry, LocalDate day, Runnable onLinked) throws SQLException {
        YunxiaoService connection = connection(owner);
        if (connection != null) {
            new WorkDialog(owner, connection, entry, day, null).showAutomaticChild(onLinked);
        }
    }

    void startTimer(Window owner, TimerService timer, LocalDate day, String initialTitle, Consumer<Boolean> onClosed)
            throws SQLException {
        YunxiaoService connection = connection(owner);
        if (connection == null) {
            onClosed.accept(false);
        } else {
            new WorkDialog(owner, connection, null, day, timer).showTimer(initialTitle, onClosed);
        }
    }

    private void showBindingDialog(Window owner, DayReview.Entry entry, LocalDate day,
                                   boolean createTask, Consumer<Binding> onBound) throws SQLException {
        YunxiaoService connection = connection(owner);
        if (connection != null) {
            new WorkDialog(owner, connection, entry, day, null).showBindingDialog(createTask, onBound);
        }
    }

    private YunxiaoService connection(Window owner) throws SQLException {
        YunxiaoService connection = settings.currentConnection();
        if (connection == null) {
            configure(owner);
            connection = settings.currentConnection();
        }
        return connection;
    }

    private static final class StatusDialog {
        private final YunxiaoService connection;
        private final Binding binding;
        private final Dialog<ButtonType> dialog;
        private final SimpleBooleanProperty busy = new SimpleBooleanProperty();
        private final Label currentLabel = label("—", "section-title", "wrap");
        private final ComboBox<Choice> target = new ComboBox<>();
        private final Label feedback = label("", "muted", "wrap");
        private Choice current;
        private boolean saving;

        StatusDialog(Window owner, YunxiaoService connection, Binding binding) {
            this.connection = connection;
            this.binding = binding;
            Button reload = button("刷新", "subtle");
            reload.setAccessibleText("刷新云效状态");
            reload.disableProperty().bind(busy);
            reload.setOnAction(event -> load(""));
            HBox currentRow = new HBox(8, currentLabel, spacer(), reload);
            currentRow.setAlignment(Pos.CENTER_LEFT);
            target.setPromptText("选择目标状态");
            target.disableProperty().bind(busy.or(Bindings.isEmpty(target.getItems())));
            feedback.managedProperty().bind(feedback.textProperty().isNotEmpty());
            VBox fields = new VBox(10, label(binding.label(), "section-title", "wrap"),
                    field("当前状态", currentRow), field("目标状态", target), feedback);
            dialog = form(owner, "云效状态", fields, "保存状态");
            Node save = dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().getFirst());
            save.disableProperty().bind(busy.or(target.valueProperty().isNull()));
            save.addEventFilter(ActionEvent.ACTION, event -> {
                event.consume();
                save();
            });
            dialog.setOnCloseRequest(event -> {
                if (saving) {
                    event.consume();
                }
            });
        }

        void show() {
            dialog.show();
            load("");
        }

        private void load(String message) {
            busy.set(true);
            target.setValue(null);
            target.getItems().clear();
            feedback.setText(message + "正在读取云效状态…");
            background(() -> connection.workItemStatus(binding), result -> {
                current = result.current();
                currentLabel.setText(current.name());
                target.getItems().setAll(result.options());
                feedback.setText(result.options().isEmpty() ? message + "此工作流没有其他状态。" : message);
                busy.set(false);
            }, error -> {
                currentLabel.setText("—");
                feedback.setText(message + "读取云效状态失败。" + errorText(error));
                busy.set(false);
            });
        }

        private void save() {
            Choice expected = current;
            Choice selected = target.getValue();
            busy.set(true);
            saving = true;
            feedback.setText("正在保存云效状态…");
            background(() -> {
                connection.updateStatus(binding, expected, selected);
                return null;
            }, result -> {
                saving = false;
                load("状态修改已提交。");
            }, error -> {
                saving = false;
                load(errorText(error));
            });
        }
    }

    private final class AssigneeSettings {
        private final Dialog<ButtonType> dialog;
        private final ButtonType saveType;
        private final YunxiaoService connection;
        private final Assignee saved;
        private final ComboBox<Choice> projects = new ComboBox<>();
        private final Button more = button("加载更多", "secondary");
        private final TextField search = new TextField();
        private final ObservableList<Choice> members = FXCollections.observableArrayList();
        private final FilteredList<Choice> filtered = new FilteredList<>(members);
        private final ListView<Choice> results = new ListView<>(filtered);
        private final Label status = label("", "muted", "wrap");
        private final Button retry = button("重试", "secondary");
        private final SimpleBooleanProperty busy = new SimpleBooleanProperty();

        AssigneeSettings(Window owner, YunxiaoService connection, Assignee saved) {
            this(owner, connection, saved, null, List.of());
        }

        AssigneeSettings(Window owner, YunxiaoService connection, Assignee saved,
                         Choice project, List<Choice> projectMembers) {
            this.connection = connection;
            this.saved = saved;
            projects.setPromptText("选择包含该成员的项目");
            projects.setMinWidth(0);
            projects.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(projects, Priority.ALWAYS);
            more.setMinWidth(Region.USE_PREF_SIZE);
            more.managedProperty().bind(more.visibleProperty());
            more.setVisible(false);
            search.setPromptText("按姓名搜索");
            results.setPrefHeight(175);
            results.setPlaceholder(label(project == null ? "请选择项目，或检查搜索条件。" : "没有匹配的负责人。", "muted"));
            search.textProperty().addListener((observable, previous, value) -> {
                results.getSelectionModel().clearSelection();
                String query = value.strip().toLowerCase(Locale.ROOT);
                filtered.setPredicate(member -> member.name().toLowerCase(Locale.ROOT).contains(query));
            });
            projects.valueProperty().addListener((observable, previous, choice) -> loadMembers(choice));
            retry.managedProperty().bind(retry.visibleProperty());
            retry.setVisible(false);
            status.managedProperty().bind(status.textProperty().isNotEmpty());
            Region projectField = project == null ? field("项目", new HBox(8, projects, more))
                    : label("当前项目  " + project.name(), "muted", "wrap");
            VBox selectors = new VBox(9, projectField, field("搜索负责人", search), results);
            selectors.disableProperty().bind(busy);
            VBox fields = new VBox(10,
                    label("新建云效任务时会自动选中该成员，每笔任务仍可从下拉框更换。", "muted", "wrap"),
                    selectors, status, retry);
            if (saved != null) {
                fields.getChildren().addFirst(label("当前默认负责人  " + saved.name(), "section-title", "wrap"));
            }
            dialog = form(owner, "云效默认负责人", fields, "保存默认负责人");
            ObservableList<ButtonType> buttons = dialog.getDialogPane().getButtonTypes();
            saveType = buttons.getFirst();
            if (saved == null) {
                buttons.set(1, new ButtonType("稍后设置", ButtonBar.ButtonData.CANCEL_CLOSE));
            }
            Node save = dialog.getDialogPane().lookupButton(saveType);
            save.disableProperty().bind(busy.or(results.getSelectionModel().selectedItemProperty().isNull()));
            bindSave(dialog, () -> connection.saveDefaultAssignee(selectedAssignee()));
            if (project == null) {
                search.disableProperty().bind(projects.valueProperty().isNull());
                dialog.setOnShown(event -> loadProjects(1));
            } else {
                installMembers(projectMembers);
                dialog.setOnShown(event -> search.requestFocus());
            }
        }

        Assignee show() {
            return dialog.showAndWait().filter(saveType::equals).isPresent() ? selectedAssignee() : null;
        }

        private Assignee selectedAssignee() {
            Choice member = results.getSelectionModel().getSelectedItem();
            if (member == null) {
                throw new IllegalArgumentException("请选择一位默认负责人。");
            }
            return new Assignee(member.id(), member.name());
        }

        private void loadProjects(int page) {
            load("正在读取项目…", () -> connection.projects(page), result -> {
                projects.getItems().addAll(result.items());
                more.setVisible(result.next() > 0);
                more.setOnAction(event -> loadProjects(result.next()));
                if (projects.getItems().isEmpty() && result.next() == 0) {
                    status.setText("没有可访问的项目。");
                }
            }, () -> loadProjects(page));
        }

        private void loadMembers(Choice project) {
            results.getSelectionModel().clearSelection();
            members.clear();
            search.clear();
            if (project != null) {
                load("正在读取项目成员…", () -> connection.members(project.id()),
                        this::installMembers, () -> loadMembers(project));
            }
        }

        private void installMembers(List<Choice> choices) {
            members.setAll(choices);
            if (choices.isEmpty()) {
                status.setText("没有可用的成员，请选择其他项目。");
            } else if (saved != null) {
                choices.stream().filter(member -> member.id().equals(saved.memberId()))
                        .findFirst().ifPresent(member -> results.getSelectionModel().select(member));
            }
        }

        private <T> void load(String message, Callable<T> request, Consumer<T> done, Runnable again) {
            busy.set(true);
            status.setText(message);
            retry.setVisible(false);
            background(request, result -> {
                if (dialog.isShowing()) {
                    busy.set(false);
                    status.setText("");
                    done.accept(result);
                }
            }, error -> {
                if (dialog.isShowing()) {
                    busy.set(false);
                    status.setText(error instanceof ApiException ? error.getMessage()
                            : "无法读取云效列表，请检查连接后重试。");
                    retry.setOnAction(event -> again.run());
                    retry.setVisible(true);
                }
            });
        }
    }

    private final class WorkDialog {
        private final ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        private final ButtonType createTask = new ButtonType("新建并关联", ButtonBar.ButtonData.OK_DONE);
        private final VBox content = new VBox(10);
        private final VBox body = new VBox(10);
        private final Label status = label("", "muted", "wrap");
        private final CheckBox retry = new CheckBox("已在云效核对，确认可以重试");
        private final YunxiaoService connection;
        private final DayReview.Entry entry;
        private final LocalDate day;
        private final TimerService timer;
        private final SimpleBooleanProperty busy = new SimpleBooleanProperty();
        private Dialog<ButtonType> dialog;
        private boolean started;
        private boolean changed;
        private Binding newBinding;

        WorkDialog(Window owner, YunxiaoService connection, DayReview.Entry entry, LocalDate day, TimerService timer) {
            this(connection, entry, day, timer);
            initWindow(owner);
        }

        WorkDialog(YunxiaoService connection, DayReview.Entry entry, LocalDate day, TimerService timer) {
            this.connection = connection;
            this.entry = entry;
            this.day = day;
            this.timer = timer;
            retry.managedProperty().bind(retry.visibleProperty());
            retry.setVisible(false);
            status.managedProperty().bind(status.textProperty().isNotEmpty());
            body.getChildren().addAll(content, status, retry);
            if (entry != null) {
                Label title = label(entry.task().title(), "section-title", "wrap");
                Label duration = label(day + "  ·  已结束 " + DayReview.duration(entry.seconds())
                        + (entry.estimatedSeconds() > 0 ? "  ·  含估算" : ""), "muted", "wrap");
                VBox context = new VBox(4, title, duration);
                context.getStyleClass().add("dialog-context");
                body.getChildren().addFirst(context);
            }
        }

        private void initWindow(Window owner) {
            dialog = new Dialog<>();
            dialog.initOwner(owner);
            dialog.setTitle("云效工时");
            dialog.setHeaderText("云效工时");
            dialog.setResizable(true);
            styleDialog(dialog, 570);
            dialog.getDialogPane().getButtonTypes().setAll(close);
            int index = body.getChildren().indexOf(content);
            body.getChildren().remove(index);
            ScrollPane scroll = new ScrollPane(content);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setPrefViewportHeight(365);
            VBox.setVgrow(scroll, Priority.ALWAYS);
            body.getChildren().add(index, scroll);
            dialog.getDialogPane().setContent(body);
            dialog.setOnCloseRequest(event -> {
                if (busy.get()) {
                    event.consume();
                }
            });
        }

        EffortOverlay effortOverlay(Binding binding, Consumer<EffortOverlay> onClose, Runnable onChanged) {
            body.getStyleClass().add("effort-form");
            TextField hours = submitForm(binding);
            EffortOverlay overlay = new EffortOverlay(body, busy, closed -> {
                onClose.accept(closed);
                if (changed) {
                    onChanged.run();
                }
            });
            hours.sceneProperty().addListener((observable, previous, scene) -> {
                if (scene != null) {
                    Platform.runLater(hours::requestFocus);
                }
            });
            return overlay;
        }

        void showBindingDialog(boolean create, Consumer<Binding> onBound) throws SQLException {
            if (create) {
                new CreationForm(connection.defaultAssignee()).show();
            } else {
                dialog.setTitle("关联已有云效任务");
                dialog.setHeaderText("关联已有云效任务");
                choose();
            }
            dialog.setOnHidden(event -> {
                if (newBinding != null) {
                    onBound.accept(newBinding);
                }
            });
            dialog.show();
        }

        void showTimer(String initialTitle, Consumer<Boolean> onClosed) throws SQLException {
            dialog.setTitle("云效计时");
            dialog.setHeaderText("从云效工作项开始计时");
            TimerPicker picker = new TimerPicker(initialTitle, connection.defaultAssignee());
            picker.show();
            dialog.setOnHidden(event -> {
                picker.cancelReads();
                onClosed.accept(started);
            });
            dialog.show();
        }

        void showAutomaticChild(Runnable onLinked) throws SQLException {
            ChildDraft draft = connection.automaticChildDraft(entry.task().id());
            dialog.setTitle("关联已创建的子任务");
            dialog.setHeaderText("关联已创建的子任务");
            ListView<Item> items = new ListView<>();
            items.setPrefHeight(235);
            items.setPlaceholder(label("此工作项下暂无可关联的子任务", "muted", "wrap"));
            Button link = button("关联所选子任务", "primary");
            link.disableProperty().bind(items.getSelectionModel().selectedItemProperty().isNull());
            link.setOnAction(event -> {
                Item item = items.getSelectionModel().getSelectedItem();
                run("正在保存关联…", () -> connection.bindAutomaticChild(entry.task().id(), item), binding -> {
                    changed = true;
                    dialog.close();
                });
            });
            content.getChildren().setAll(
                    field("父工作项", label(draft.project().name() + " / " + draft.parent(), "section-title", "wrap")),
                    items, link);
            run("正在读取子任务…", () -> connection.subtasks(draft.parent().id()).stream()
                    .filter(item -> draft.project().id().equals(item.projectId()))
                    .sorted(Comparator.comparing(item -> !draft.title().equals(item.subject())))
                    .toList(), items.getItems()::setAll);
            showWhenChanged(onLinked);
        }

        private void showWhenChanged(Runnable onChanged) {
            dialog.setOnHidden(event -> {
                if (changed) {
                    onChanged.run();
                }
            });
            dialog.show();
        }

        private void finishTiming() {
            started = true;
            dialog.close();
        }

        private final class TimerPicker {
            private final Picker<Choice> projects = new Picker<>();
            private final ToggleGroup kinds = new ToggleGroup();
            private final TextField query = new TextField();
            private final ListView<Item> items = new ListView<>();
            private final Button more = button("加载更多", "secondary");
            private final Button direct = button("直接计时", "primary");
            private final Button create = button("创建子任务并计时…", "primary");
            private final SimpleBooleanProperty childTypesAvailable = new SimpleBooleanProperty();
            private final String initialTitle;
            private final Assignee defaultAssignee;
            private VBox pane;
            private Task<Page<Item>> searchRequest;
            private Task<List<Choice>> typesRequest;
            private int searchRevision;

            TimerPicker(String initialTitle, Assignee defaultAssignee) {
                this.initialTitle = initialTitle;
                this.defaultAssignee = defaultAssignee;
            }

            void show() {
                projects.combo.setPromptText("选择云效项目");
                query.setPromptText("输入标题或完整编号");
                items.setPrefHeight(207);
                items.setPlaceholder(label("选择项目后查看工作项", "muted", "wrap"));
                RadioButton requirements = kindOption("需求", WorkItemKind.REQUIREMENT);
                RadioButton tasks = kindOption("独立任务", WorkItemKind.INDEPENDENT_TASK);
                RadioButton subtasks = kindOption("子任务", WorkItemKind.SUBTASK);
                requirements.setSelected(true);
                kinds.selectedToggleProperty().addListener((observable, old, selected) -> search());
                HBox filters = new HBox(12, label("工作项", "field-label"), spacer(), requirements, tasks, subtasks);
                filters.setAlignment(Pos.CENTER_LEFT);
                Button search = button("搜索", "secondary");
                search.disableProperty().bind(projects.combo.valueProperty().isNull());
                query.disableProperty().bind(projects.combo.valueProperty().isNull());
                search.setOnAction(event -> search());
                query.setOnAction(event -> search());
                HBox.setHgrow(query, Priority.ALWAYS);
                projects.combo.valueProperty().addListener((observable, old, project) -> search());
                items.getSelectionModel().selectedItemProperty().addListener(
                        (observable, old, item) -> select(item));
                direct.disableProperty().bind(items.getSelectionModel().selectedItemProperty().isNull());
                direct.visibleProperty().bind(requirements.selectedProperty().not());
                direct.managedProperty().bind(direct.visibleProperty());
                direct.setOnAction(event -> startSelected());
                create.disableProperty().bind(childTypesAvailable.not());
                create.visibleProperty().bind(requirements.selectedProperty().or(childTypesAvailable));
                create.managedProperty().bind(create.visibleProperty());
                requirements.selectedProperty().addListener((observable, old, selected) -> {
                    create.getStyleClass().remove(selected ? "secondary" : "primary");
                    create.getStyleClass().add(selected ? "primary" : "secondary");
                });
                more.managedProperty().bind(more.visibleProperty());
                more.setVisible(false);
                VBox searchFields = new VBox(5, filters, new HBox(8, query, search));
                pane = new VBox(10, field("项目", projects.row), searchFields, items, new HBox(8, direct, create, more));
                content.getChildren().setAll(pane);
                projects.load(1, connection::projects);
            }

            private RadioButton kindOption(String label, WorkItemKind kind) {
                RadioButton option = new RadioButton(label);
                option.setToggleGroup(kinds);
                option.setUserData(kind);
                return option;
            }

            private void search() {
                cancelReads();
                items.getItems().clear();
                more.setVisible(false);
                Choice project = projects.combo.getValue();
                if (project == null) {
                    items.setPlaceholder(label("选择项目后查看工作项", "muted", "wrap"));
                } else if (kinds.getSelectedToggle() instanceof RadioButton selected) {
                    items.setPlaceholder(label("没有找到匹配的" + selected.getText(), "muted", "wrap"));
                    load(project, (WorkItemKind) selected.getUserData(), query.getText().strip(), 1);
                }
            }

            private void load(Choice project, WorkItemKind kind, String keyword, int page) {
                if (searchRequest != null) {
                    searchRequest.cancel();
                }
                int revision = ++searchRevision;
                more.setDisable(true);
                status.setText("正在查找云效工作项…");
                searchRequest = background(() -> connection.timerItems(project.id(), kind, keyword, page), result -> {
                    if (revision != searchRevision) {
                        return;
                    }
                    searchRequest = null;
                    more.setDisable(false);
                    status.setText("");
                    items.getItems().addAll(result.items());
                    more.setVisible(result.next() > 0);
                    more.setOnAction(event -> load(project, kind, keyword, result.next()));
                }, error -> {
                    if (revision != searchRevision) {
                        return;
                    }
                    searchRequest = null;
                    more.setDisable(false);
                    status.setText(errorText(error));
                });
            }

            private void select(Item item) {
                if (typesRequest != null) {
                    typesRequest.cancel();
                }
                childTypesAvailable.set(false);
                if (searchRequest == null) {
                    status.setText("");
                }
                if (item == null) {
                    return;
                }
                Choice project = projects.combo.getValue();
                typesRequest = background(() -> connection.taskTypes(project.id(), item.typeId()), types -> {
                    if (isSelected(project, item)) {
                        childTypesAvailable.set(!types.isEmpty());
                        if (types.isEmpty() && WorkItemKind.REQUIREMENT.matches(item) && searchRequest == null) {
                            status.setText("这个需求没有可用的子任务类型。");
                        }
                        create.setOnAction(event -> {
                            cancelReads();
                            new CreationForm(defaultAssignee, new ParentSelection(project, item, types),
                                    initialTitle, this::back).show();
                        });
                    }
                }, error -> {
                    if (isSelected(project, item) && !busy.get() && searchRequest == null) {
                        status.setText(errorText(error));
                    }
                });
            }

            private boolean isSelected(Choice project, Item item) {
                return dialog.isShowing() && content.getChildren().contains(pane)
                        && project.equals(projects.combo.getValue())
                        && item.equals(items.getSelectionModel().getSelectedItem());
            }

            private void startSelected() {
                cancelReads();
                Choice project = projects.combo.getValue();
                Item item = items.getSelectionModel().getSelectedItem();
                run("正在开始计时…", () -> {
                    timer.start(connection.timerTask(project, item));
                    return null;
                }, ignored -> finishTiming());
            }

            private void back() {
                resetRetry();
                status.setText("");
                dialog.setTitle("云效计时");
                dialog.setHeaderText("从云效工作项开始计时");
                dialog.getDialogPane().setPrefWidth(570);
                dialog.getDialogPane().getButtonTypes().setAll(close);
                content.getChildren().setAll(pane);
            }

            private void cancelReads() {
                searchRevision++;
                if (searchRequest != null) {
                    searchRequest.cancel();
                    searchRequest = null;
                    more.setDisable(false);
                }
                if (typesRequest != null) {
                    typesRequest.cancel();
                    typesRequest = null;
                }
            }
        }

        private void choose() {
            Picker<Choice> projects = new Picker<>();
            Picker<Item> parents = new Picker<>();
            Picker<Item> tasks = new Picker<>();
            ToggleGroup modes = new ToggleGroup();
            RadioButton subtask = new RadioButton("需求下的子任务");
            RadioButton independent = new RadioButton("项目独立任务");
            subtask.setToggleGroup(modes);
            independent.setToggleGroup(modes);
            subtask.setSelected(true);
            projects.combo.setPromptText("选择项目");
            parents.combo.setPromptText("选择需求");
            tasks.combo.promptTextProperty().bind(Bindings.when(subtask.selectedProperty())
                    .then("选择已有子任务").otherwise("选择已有独立任务"));
            Button bind = button("关联所选任务", "primary");
            bind.disableProperty().bind(tasks.combo.valueProperty().isNull());
            bind.setOnAction(event -> {
                Choice project = projects.combo.getValue();
                Item parent = parents.combo.getValue();
                Item task = tasks.combo.getValue();
                run("正在保存关联…", () -> connection.bind(entry.task().id(), project, parent, task),
                        this::showNewBinding);
            });

            Runnable loadForProject = () -> {
                parents.clear();
                tasks.clear();
                status.setText("");
                Choice project = projects.combo.getValue();
                if (project == null) {
                    return;
                }
                if (subtask.isSelected()) {
                    parents.load(1, page -> connection.requirements(project.id(), page));
                } else {
                    tasks.load(1, page -> connection.projectTasks(project.id(), page));
                }
            };
            projects.combo.valueProperty().addListener((observable, old, project) -> loadForProject.run());
            subtask.selectedProperty().addListener((observable, old, selected) -> loadForProject.run());
            parents.combo.valueProperty().addListener((observable, old, parent) -> {
                tasks.clear();
                if (parent != null) {
                    String projectId = projects.combo.getValue().id();
                    run("正在读取子任务…", () -> connection.subtasks(parent.id()).stream()
                            .filter(child -> projectId.equals(child.projectId())).toList(), items -> {
                        tasks.combo.getItems().setAll(items);
                        status.setText(items.isEmpty() ? "这个需求下没有可关联的任务。" : "");
                    });
                }
            });

            VBox parentField = field("父需求", parents.row);
            parentField.visibleProperty().bind(subtask.selectedProperty());
            parentField.managedProperty().bind(parentField.visibleProperty());
            VBox chooser = new VBox(10, field("项目", projects.row),
                    field("任务归属", new HBox(16, subtask, independent)), parentField,
                    field("已有任务", tasks.row), bind,
                    label("云效关联应用于这个本地事项的所有时间段。跨项目的同名工作请分别建事项。",
                            "muted", "wrap"));
            content.getChildren().setAll(chooser);
            projects.load(1, connection::projects);
        }

        private final class CreationForm {
            private final Picker<Choice> projects = new Picker<>();
            private final Picker<Item> parents = new Picker<>();
            private final CheckBox useParent = new CheckBox("添加父需求");
            private final TextField title;
            private final TextArea description = new TextArea();
            private final ComboBox<Choice> types = new ComboBox<>();
            private final Label typeHint = label("任务类型随归属项目变化。", "muted", "wrap");
            private final ComboBox<Choice> assignees = new ComboBox<>();
            private final Label assigneeHint = label("", "muted", "wrap");
            private Assignee preferredAssignee;
            private final VBox extra = new VBox(9);
            private final Map<Field, Supplier<String>> inputs = new LinkedHashMap<>();
            private final SimpleBooleanProperty ready = new SimpleBooleanProperty();
            private final ParentSelection selection;
            private final Runnable back;
            private boolean descriptionRequired;

            CreationForm(Assignee saved) {
                this(saved, null, entry.task().title(), null);
            }

            CreationForm(Assignee saved, ParentSelection selection, String initialTitle, Runnable back) {
                preferredAssignee = saved;
                this.selection = selection;
                this.back = back;
                title = new TextField(initialTitle);
                ButtonType action = selection == null ? createTask
                        : new ButtonType("开始计时", ButtonBar.ButtonData.OK_DONE);
                dialog.getDialogPane().getButtonTypes().addFirst(action);
                Button create = (Button) dialog.getDialogPane().lookupButton(action);
                create.disableProperty().bind(content.disableProperty().or(ready.not())
                        .or(assignees.valueProperty().isNull()).or(Bindings.createBooleanBinding(
                                this::missingTitle, title.textProperty())));
                if (selection != null) {
                    title.setOnAction(event -> create.fire());
                }
                create.addEventFilter(ActionEvent.ACTION, event -> {
                    event.consume();
                    save();
                });
            }

            void show() {
                String heading = selection == null ? "新建云效任务" : "子任务计时";
                dialog.setTitle(heading);
                dialog.setHeaderText(heading);
                dialog.getDialogPane().setPrefWidth(720);
                projects.combo.setPromptText("选择归属项目");
                parents.combo.setPromptText("选择父需求");
                types.setPromptText("选择工作项类型");
                assignees.setPromptText("选择负责人");
                description.setPromptText("补充任务背景、结果或验收要求");
                description.setWrapText(true);
                description.setPrefRowCount(8);

                VBox parentField = field("父需求", parents.row);
                parentField.visibleProperty().bind(useParent.selectedProperty());
                parentField.managedProperty().bind(parentField.visibleProperty());
                useParent.disableProperty().bind(projects.combo.valueProperty().isNull());
                VBox draft = new VBox(10);
                if (selection != null) {
                    draft.getChildren().add(field("父工作项", label(selection.item().toString(), "section-title", "wrap")));
                    title.setPromptText("这次具体要做什么？");
                }
                draft.getChildren().add(field("标题", title));
                if (selection != null) {
                    draft.getChildren().add(titleActions());
                }
                draft.getChildren().add(field("描述（支持 Markdown）", description));
                draft.getChildren().add(label("计划开始和完成均为 " + day, "muted"));
                if (selection == null) {
                    draft.getChildren().add(label("新建后会关联到当前事项，工时可在下一步核对并提交。", "muted", "wrap"));
                } else {
                    draft.getChildren().add(label("首次结束时创建子任务并登记工时，之后继续计时会更新当天累计。",
                            "muted", "wrap"));
                }
                draft.setMinWidth(200);
                HBox.setHgrow(draft, Priority.ALWAYS);
                VBox typeField = field("工作项类型", types);
                typeField.getChildren().add(typeHint);
                VBox fields = new VBox(9, label("基础字段", "section-title"));
                if (selection == null) {
                    fields.getChildren().add(field("归属项目", projects.row));
                }
                fields.getChildren().addAll(typeField, createAssigneeField(), extra);
                if (selection == null) {
                    fields.getChildren().addAll(useParent, parentField);
                }
                fields.setPrefWidth(265);
                fields.setMinWidth(210);
                HBox form = new HBox(18, draft, fields);
                if (selection == null) {
                    content.getChildren().setAll(form);
                    connectFieldChanges();
                    projects.load(1, connection::projects);
                } else {
                    Button returnToItems = button("返回工作项", "subtle");
                    returnToItems.setOnAction(event -> back.run());
                    content.getChildren().setAll(returnToItems, form);
                    projects.combo.setValue(selection.project());
                    parents.combo.setValue(selection.item());
                    connectFieldChanges();
                    loadOptions(selection.project(), selection.types());
                }
            }

            private boolean missingTitle() {
                String subject = title.getText().strip();
                return subject.isEmpty() || selection != null && TITLE_ACTIONS.contains(subject);
            }

            private HBox titleActions() {
                HBox actions = new HBox(6);
                for (String action : TITLE_ACTIONS) {
                    Button prefix = button(action, "secondary");
                    prefix.disableProperty().bind(title.editableProperty().not());
                    prefix.setOnAction(event -> {
                        String subject = title.getText().strip();
                        for (String previous : TITLE_ACTIONS) {
                            if (subject.startsWith(previous)) {
                                subject = subject.substring(previous.length()).stripLeading();
                                break;
                            }
                        }
                        title.setText(action + subject);
                        title.requestFocus();
                        title.end();
                    });
                    actions.getChildren().add(prefix);
                }
                return actions;
            }

            private VBox createAssigneeField() {
                VBox assigneeField = field("负责人", assignees);
                Button settings = button("设置默认…", "subtle");
                settings.visibleProperty().bind(Bindings.isNotEmpty(assignees.getItems()));
                settings.managedProperty().bind(settings.visibleProperty());
                settings.setOnAction(event -> chooseDefaultAssignee());
                Node assigneeCaption = assigneeField.getChildren().removeFirst();
                HBox assigneeHeader = new HBox(5, assigneeCaption, spacer(), settings);
                assigneeHeader.setAlignment(Pos.CENTER_LEFT);
                assigneeField.getChildren().addFirst(assigneeHeader);
                assigneeHint.visibleProperty().bind(assigneeHint.textProperty().isNotEmpty()
                        .and(assignees.valueProperty().isNull()));
                assigneeHint.managedProperty().bind(assigneeHint.visibleProperty());
                assigneeField.getChildren().add(assigneeHint);
                return assigneeField;
            }

            private void connectFieldChanges() {
                projects.combo.valueProperty().addListener((observable, old, project) -> loadProject(project));
                types.valueProperty().addListener((observable, old, type) -> {
                    if (type != null) {
                        loadFields(type);
                    }
                });
                parents.combo.valueProperty().addListener((observable, old, parent) -> {
                    if (parent != null) {
                        loadTypes(parent);
                    }
                });
                useParent.setOnAction(event -> {
                    Choice project = projects.combo.getValue();
                    if (useParent.isSelected()) {
                        parents.load(1, page -> connection.requirements(project.id(), page));
                    } else {
                        boolean hadParent = parents.combo.getValue() != null;
                        parents.clear();
                        if (hadParent) {
                            loadTypes(null);
                        }
                    }
                });
            }

            private void loadProject(Choice project) {
                clearTypes();
                useParent.setSelected(false);
                parents.clear();
                assigneeHint.setText("");
                assignees.setValue(null);
                assignees.getItems().clear();
                if (project == null) {
                    return;
                }
                loadOptions(project, null);
            }

            private void loadOptions(Choice project, List<Choice> childTypes) {
                run("正在读取任务类型和项目成员…", () -> new CreationOptions(
                        childTypes == null ? connection.taskTypes(project.id()) : childTypes,
                        connection.members(project.id())), options -> {
                    assignees.getItems().setAll(options.members());
                    if (options.members().isEmpty()) {
                        status.setText("没有可用的负责人，请检查项目成员和访问权限。");
                    } else {
                        if (preferredAssignee == null) {
                            assigneeHint.setText("尚未设置默认负责人。");
                        } else {
                            selectAssignee(preferredAssignee.memberId());
                            if (assignees.getValue() == null) {
                                assigneeHint.setText("当前项目无法选择「" + preferredAssignee.name() + "」，请手动选择。");
                            }
                        }
                        installTypes(options.types());
                    }
                });
            }

            private void chooseDefaultAssignee() {
                Window owner = dialog.getDialogPane().getScene().getWindow();
                Assignee selected = new AssigneeSettings(owner, connection, preferredAssignee,
                        projects.combo.getValue(), assignees.getItems()).show();
                if (selected != null) {
                    preferredAssignee = selected;
                    selectAssignee(selected.memberId());
                    assigneeHint.setText("");
                }
            }

            private void loadTypes(Item parent) {
                clearTypes();
                String projectId = projects.combo.getValue().id();
                run("正在读取可用任务类型…",
                        () -> parent == null ? connection.taskTypes(projectId)
                                : connection.taskTypes(projectId, parent.typeId()), this::installTypes);
            }

            private void clearTypes() {
                resetRetry();
                ready.set(false);
                types.setValue(null);
                types.getItems().clear();
                typeHint.setText("任务类型随归属项目变化。");
                extra.getChildren().clear();
                inputs.clear();
            }

            private void installTypes(List<Choice> choices) {
                types.getItems().setAll(choices);
                String scope = parents.combo.getValue() == null
                        ? "「" + projects.combo.getValue().name() + "」" : "当前父项";
                typeHint.setText(scope + "可选 " + choices.size() + " 种任务类型。");
                if (choices.isEmpty()) {
                    status.setText("这里没有可用的任务类型，请检查云效项目配置。");
                } else {
                    types.getSelectionModel().selectFirst();
                }
            }

            private void loadFields(Choice type) {
                resetRetry();
                ready.set(false);
                inputs.clear();
                extra.getChildren().clear();
                descriptionRequired = false;
                String projectId = projects.combo.getValue().id();
                run("正在读取任务模板…", () -> connection.creationFields(projectId, type.id()), fields -> {
                    List<String> unsupported = new ArrayList<>();
                    for (Field field : fields) {
                        String plannedDate = field.plannedDate(day);
                        if (!plannedDate.isBlank()) {
                            inputs.put(field, () -> plannedDate);
                            continue;
                        }
                        String id = field.id().toLowerCase(Locale.ROOT);
                        switch (id) {
                            case "assignedto", "assignee" -> applyAssigneeDefault(field);
                            case "description" -> descriptionRequired = field.required();
                            default -> {
                                boolean priority = "priority".equals(id) || "优先级".equals(field.name());
                                if ("priority".equals(id) || (priority || field.required())
                                        && Set.of("CustomField", "SystemCustomField").contains(field.kind())) {
                                    inputs.put(field, addCustomField(extra, field, priority));
                                } else if (field.required() && field.defaultValue().isBlank()
                                        && !Set.of("subject", "title", "spaceid", "workitemtypeid", "parentid").contains(id)) {
                                    unsupported.add(field.name());
                                }
                            }
                        }
                    }
                    if (unsupported.isEmpty()) {
                        ready.set(true);
                    } else {
                        status.setText("此模板需要填写「" + String.join("、", unsupported)
                                + "」。请在云效新建后，再关联已有任务。");
                    }
                });
            }

            private void applyAssigneeDefault(Field field) {
                if (preferredAssignee == null && assignees.getValue() == null) {
                    selectAssignee(field.defaultValue());
                }
            }

            private void selectAssignee(String memberId) {
                assignees.getItems().stream().filter(member -> member.id().equals(memberId))
                        .findFirst().ifPresent(assignees::setValue);
            }

            private void save() {
                Choice project = projects.combo.getValue();
                Item parent = parents.combo.getValue();
                Choice type = types.getValue();
                Choice assignee = assignees.getValue();
                String subject = title.getText().strip();
                String body = description.getText();
                if (project == null || type == null || missingTitle() || assignee == null) {
                    status.setText("请填写标题，并选择归属项目、工作项类型和负责人。");
                    return;
                }
                if (useParent.isSelected() && parent == null) {
                    status.setText("请选择父需求，或取消添加父需求。");
                    return;
                }
                if (descriptionRequired && body.isBlank()) {
                    status.setText("请填写任务描述。");
                    return;
                }
                Map<String, String> custom = new LinkedHashMap<>();
                for (Map.Entry<Field, Supplier<String>> input : inputs.entrySet()) {
                    String value = input.getValue().get().strip();
                    if (value.isEmpty()) {
                        if (input.getKey().required()) {
                            status.setText("请填写「" + input.getKey().name() + "」。");
                            return;
                        }
                    } else {
                        custom.put(input.getKey().id(), value);
                    }
                }
                boolean retryConfirmed = retry.isSelected();
                resetRetry();
                Map<String, String> values = Map.copyOf(custom);
                if (selection == null) {
                    run("正在新建云效任务…", () -> connection.create(entry.task().id(), project, parent, type.id(),
                            subject, assignee.id(), body, values, retryConfirmed), WorkDialog.this::showNewBinding);
                } else {
                    ChildDraft draft = new ChildDraft(project, parent, type.id(), subject, assignee.id(), body, values);
                    run("正在开始计时…", () -> {
                        long taskId = connection.prepareAutomaticChild(draft);
                        timer.start(taskId);
                        return null;
                    }, ignored -> finishTiming());
                }
            }
        }

        private Supplier<String> addCustomField(VBox target, Field field, boolean priority) {
            if ("list".equalsIgnoreCase(field.format()) && !field.options().isEmpty()) {
                ComboBox<Choice> values = new ComboBox<>();
                values.setPromptText("请选择" + field.name());
                values.getItems().setAll(field.options());
                field.options().stream().filter(option -> option.id().equals(field.defaultValue()))
                        .findFirst().ifPresent(values::setValue);
                if (priority && values.getValue() == null && field.defaultValue().isBlank()) {
                    field.options().stream().filter(option -> "中".equals(option.name())
                                    || "medium".equalsIgnoreCase(option.name()))
                            .findFirst().ifPresent(values::setValue);
                }
                target.getChildren().add(field(field.name(), values));
                return () -> values.getValue() == null ? "" : values.getValue().id();
            }
            TextField value = new TextField(field.defaultValue());
            value.setPromptText("请输入云效字段值，多个值用英文逗号分隔");
            target.getChildren().add(field(field.name(), value));
            return value::getText;
        }

        private void showNewBinding(Binding binding) {
            newBinding = binding;
            dialog.close();
        }

        private TextField submitForm(Binding binding) {
            resetRetry();
            BigDecimal amount = YunxiaoService.effortHours(entry.seconds());
            TextField hours = new TextField(amount.stripTrailingZeros().toPlainString());
            Button submit = button("提交工时", "primary");
            Label manualHelp = label("", "muted", "wrap");
            Button open = button("在云效打开任务", "secondary");
            Button copy = button("复制登记说明", "secondary");
            Button confirm = button("核对网页工时", "primary");
            VBox manual = new VBox(8, manualHelp, new HBox(8, open, copy, confirm));
            manual.managedProperty().bind(manual.visibleProperty());
            manual.setVisible(false);
            open.managedProperty().bind(open.visibleProperty());
            copy.managedProperty().bind(copy.visibleProperty());
            String page = connection.workItemPage(binding.childId());
            open.setVisible(!page.isBlank());
            open.setOnAction(event -> openPage.accept(page));
            Consumer<Boolean> save = verify -> {
                String input = hours.getText().strip();
                if (!input.matches("[0-9]{1,4}(\\.[0-9]{1,2})?") || new BigDecimal(input).signum() <= 0) {
                    status.setText("请输入大于 0 的小时数，最多两位小数。");
                    return;
                }
                BigDecimal actual = new BigDecimal(input);
                boolean retryConfirmed = retry.isSelected();
                resetRetry();
                run(verify ? "正在核对云效登记…" : "正在提交工时…", () -> {
                    if (verify) {
                        connection.confirmWebEffort(entry.task().id(), binding, day, actual, retryConfirmed);
                    } else {
                        connection.submit(entry.task().id(), binding, day, actual, retryConfirmed);
                    }
                    return actual;
                }, saved -> {
                    changed = true;
                    manual.setVisible(false);
                    status.setText("已同步 " + day + " 的 " + saved.toPlainString() + " 小时。");
                }, failure -> {
                    if (failure instanceof YunxiaoService.PartialSubmission) {
                        changed = true;
                    }
                    if (failure instanceof YunxiaoService.ManualEffortRequired required) {
                        String description = required.newRecordDescription();
                        copy.setVisible(!description.isBlank());
                        copy.setOnAction(event -> {
                            ClipboardContent data = new ClipboardContent();
                            data.putString(description);
                            if (Clipboard.getSystemClipboard().setContent(data)) {
                                status.setText("已复制登记说明。");
                            }
                        });
                        manualHelp.setText((description.isBlank()
                                ? "请在云效编辑当天的原登记。"
                                : "请在云效核对当天工时，优先编辑已有登记，并粘贴收工记提供的登记说明。")
                                + "勾选「包含休息日」并保存，再填入网页保存的小时数，点「核对网页工时」。");
                        manual.setVisible(true);
                    }
                });
            };
            submit.setOnAction(event -> save.accept(false));
            confirm.setOnAction(event -> save.accept(true));
            content.getChildren().setAll(field("已关联云效任务", label(binding.label(), "section-title", "wrap")),
                    field("实际工时（小时）", hours),
                    label("同一事项同一天再次提交会更新原登记。收工记创建的任务会同步计划日期和预计工时。",
                            "muted", "wrap"), submit, manual);
            return hours;
        }

        private <T> void run(String message, Callable<T> action, Consumer<T> done) {
            run(message, action, done, ignored -> {});
        }

        private <T> void run(String message, Callable<T> action, Consumer<T> done, Consumer<Throwable> failed) {
            status.setText(message);
            setBusy(true);
            background(action, result -> {
                setBusy(false);
                status.setText("");
                done.accept(result);
            }, error -> {
                setBusy(false);
                retry.setVisible(error instanceof YunxiaoService.UncertainWrite
                        || error instanceof YunxiaoService.PartialSubmission && error.getCause() instanceof YunxiaoService.UncertainWrite);
                status.setText(errorText(error));
                failed.accept(error);
            });
        }

        private void setBusy(boolean value) {
            busy.set(value);
            content.setDisable(value);
            retry.setDisable(value);
            if (dialog != null) {
                dialog.getDialogPane().lookupButton(close).setDisable(value);
            }
        }

        private void resetRetry() {
            retry.setSelected(false);
            retry.setVisible(false);
        }

        private final class Picker<T> {
            private final ComboBox<T> combo = new ComboBox<>();
            private final Button more = button("加载更多", "secondary");
            private final HBox row = new HBox(8, combo, more);

            Picker() {
                combo.setMinWidth(0);
                combo.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(combo, Priority.ALWAYS);
                more.setMinWidth(Region.USE_PREF_SIZE);
                more.managedProperty().bind(more.visibleProperty());
                more.setVisible(false);
            }

            void clear() {
                combo.getItems().clear();
                combo.setValue(null);
                more.setVisible(false);
            }

            void load(int page, Fetch<T> fetch) {
                run("正在读取云效列表…", () -> fetch.get(page), result -> {
                    combo.getItems().addAll(result.items());
                    more.setVisible(result.next() > 0);
                    more.setOnAction(event -> load(result.next(), fetch));
                    if (combo.getItems().isEmpty() && result.next() == 0) {
                        status.setText("没有找到可访问的记录。");
                    }
                });
            }
        }
    }

    static String errorText(Throwable error) {
        if (error instanceof YunxiaoService.PartialSubmission partial) {
            return partial.getMessage() + errorText(partial.getCause());
        }
        if (error instanceof ApiException || error instanceof YunxiaoService.UncertainWrite
                || error instanceof YunxiaoService.ManualEffortRequired
                || error instanceof IllegalArgumentException) {
            return error.getMessage();
        }
        if (error instanceof ProtocolException) {
            return "云效返回了无法识别的数据。若刚提交过，请再次操作以核对云效中的记录。";
        }
        if (error instanceof SQLException) {
            return "本地记录保存失败。若云效可能已写入，再次操作时会先核对。";
        }
        if (error instanceof IOException || error instanceof InterruptedException) {
            return "云效请求未能确认。请检查网络后重试，未确认的写入会先核对。";
        }
        return "云效操作失败，请检查设置后重试。";
    }

    private record CreationOptions(List<Choice> types, List<Choice> members) {}

    private record ParentSelection(Choice project, Item item, List<Choice> types) {}

    @FunctionalInterface
    private interface Fetch<T> {
        Page<T> get(int page) throws IOException, InterruptedException;
    }
}
