package com.sg.shougongji.ui;

import com.sg.shougongji.model.DayReview;
import com.sg.shougongji.model.Yunxiao.ApiException;
import com.sg.shougongji.model.Yunxiao.Assignee;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.model.Yunxiao.Choice;
import com.sg.shougongji.model.Yunxiao.ConnectionSettings;
import com.sg.shougongji.model.Yunxiao.Field;
import com.sg.shougongji.model.Yunxiao.Item;
import com.sg.shougongji.model.Yunxiao.Page;
import com.sg.shougongji.model.Yunxiao.ProtocolException;
import com.sg.shougongji.service.YunxiaoService;
import com.sg.shougongji.service.YunxiaoSettingsService;
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
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.sg.shougongji.ui.UiSupport.bindSave;
import static com.sg.shougongji.ui.UiSupport.button;
import static com.sg.shougongji.ui.UiSupport.field;
import static com.sg.shougongji.ui.UiSupport.form;
import static com.sg.shougongji.ui.UiSupport.label;
import static com.sg.shougongji.ui.UiSupport.spacer;
import static com.sg.shougongji.ui.UiSupport.styleDialog;

final class YunxiaoDialogs {
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

    void show(Window owner, DayReview.Entry entry, LocalDate day, Binding binding) throws SQLException {
        show(owner, entry, day, binding, false);
    }

    void create(Window owner, DayReview.Entry entry, LocalDate day) throws SQLException {
        show(owner, entry, day, null, true);
    }

    private void show(Window owner, DayReview.Entry entry, LocalDate day, Binding binding,
                      boolean createTask) throws SQLException {
        YunxiaoService connection = settings.currentConnection();
        if (connection == null) {
            configure(owner);
            connection = settings.currentConnection();
            if (connection == null) {
                return;
            }
        }
        if (binding != null && !binding.scope().equals(connection.scope())) {
            throw new IllegalArgumentException("该事项已绑定到其他云效组织或实例，请在「云效设置」中切换连接。");
        }
        new WorkDialog(owner, connection, entry, day).show(binding, createTask);
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
        private final Dialog<ButtonType> dialog = new Dialog<>();
        private final ButtonType close = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        private final ButtonType createTask = new ButtonType("新建并关联", ButtonBar.ButtonData.OK_DONE);
        private final VBox content = new VBox(10);
        private final Label status = label("", "muted", "wrap");
        private final CheckBox retry = new CheckBox("已在云效核对，确认可以重试");
        private final YunxiaoService connection;
        private final DayReview.Entry entry;
        private final LocalDate day;
        private boolean busy;

        WorkDialog(Window owner, YunxiaoService connection, DayReview.Entry entry, LocalDate day) {
            this.connection = connection;
            this.entry = entry;
            this.day = day;
            dialog.initOwner(owner);
            dialog.setTitle("云效工时");
            dialog.setHeaderText("云效工时");
            dialog.setResizable(true);
            styleDialog(dialog, 570);
            dialog.getDialogPane().getButtonTypes().setAll(close);
            ScrollPane scroll = new ScrollPane(content);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setPrefViewportHeight(365);
            VBox.setVgrow(scroll, Priority.ALWAYS);
            retry.managedProperty().bind(retry.visibleProperty());
            retry.setVisible(false);
            Label title = label(entry.task().title(), "section-title", "wrap");
            Label duration = label(day + "  ·  已结束 " + DayReview.duration(entry.seconds())
                    + (entry.estimatedSeconds() > 0 ? "  ·  含估算" : ""), "muted", "wrap");
            VBox context = new VBox(4, title, duration);
            context.getStyleClass().add("dialog-context");
            status.managedProperty().bind(status.textProperty().isNotEmpty());
            dialog.getDialogPane().setContent(new VBox(10, context, scroll, status, retry));
            dialog.setOnCloseRequest(event -> {
                if (busy) {
                    event.consume();
                }
            });
        }

        void show(Binding binding, boolean create) throws SQLException {
            if (create) {
                new CreationForm(connection.defaultAssignee()).show();
            } else if (binding == null) {
                dialog.setTitle("关联已有云效任务");
                dialog.setHeaderText("关联已有云效任务");
                choose();
            } else {
                submitForm(binding);
            }
            dialog.showAndWait();
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
                run("正在保存关联…", () -> connection.bind(entry.task().id(), project, parent, task), this::submitForm);
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
            private final TextField title = new TextField(entry.task().title());
            private final TextArea description = new TextArea();
            private final ComboBox<Choice> types = new ComboBox<>();
            private final Label typeHint = label("任务类型随归属项目变化。", "muted", "wrap");
            private final ComboBox<Choice> assignees = new ComboBox<>();
            private final Label assigneeHint = label("", "muted", "wrap");
            private Assignee preferredAssignee;
            private final VBox extra = new VBox(9);
            private final Map<Field, Supplier<String>> inputs = new LinkedHashMap<>();
            private final SimpleBooleanProperty ready = new SimpleBooleanProperty();
            private boolean descriptionRequired;

            CreationForm(Assignee saved) {
                preferredAssignee = saved;
                dialog.getDialogPane().getButtonTypes().addFirst(createTask);
                Node create = dialog.getDialogPane().lookupButton(createTask);
                create.disableProperty().bind(content.disableProperty().or(ready.not()).or(assignees.valueProperty().isNull()));
                create.addEventFilter(ActionEvent.ACTION, event -> {
                    event.consume();
                    save();
                });
            }

            void show() {
                dialog.setTitle("新建云效任务");
                dialog.setHeaderText("新建云效任务");
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
                VBox draft = new VBox(10, field("标题", title), field("描述（支持 Markdown）", description),
                        label("计划开始和完成均为 " + day, "muted"),
                        label("新建后会关联到当前事项，工时可在下一步核对并提交。", "muted", "wrap"));
                draft.setMinWidth(200);
                HBox.setHgrow(draft, Priority.ALWAYS);
                VBox typeField = field("工作项类型", types);
                typeField.getChildren().add(typeHint);
                VBox fields = new VBox(9, label("基础字段", "section-title"), field("归属项目", projects.row),
                        typeField, createAssigneeField(), extra, useParent, parentField);
                fields.setPrefWidth(265);
                fields.setMinWidth(210);
                content.getChildren().setAll(new HBox(18, draft, fields));

                connectFieldChanges();
                projects.load(1, connection::projects);
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
                run("正在读取项目任务类型和成员…", () -> new CreationOptions(connection.taskTypes(project.id()),
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
                        ? "「" + projects.combo.getValue().name() + "」" : "当前父需求";
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
                if (project == null || type == null || subject.isEmpty() || assignee == null) {
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
                run("正在新建云效任务…", () -> connection.create(entry.task().id(), project, parent, type.id(),
                        subject, assignee.id(), body, Map.copyOf(custom), retryConfirmed), WorkDialog.this::submitForm);
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

        private void submitForm(Binding binding) {
            resetRetry();
            dialog.getDialogPane().getButtonTypes().remove(createTask);
            dialog.setTitle("云效工时");
            dialog.setHeaderText("云效工时");
            BigDecimal amount = BigDecimal.valueOf(entry.seconds())
                    .divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
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
                    manual.setVisible(false);
                    status.setText("已同步 " + day + " 的 " + saved.toPlainString() + " 小时。");
                }, failure -> {
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
            busy = value;
            content.setDisable(value);
            retry.setDisable(value);
            dialog.getDialogPane().lookupButton(close).setDisable(value);
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

    private static <T> void background(Callable<T> action, Consumer<T> done, Consumer<Throwable> failed) {
        Task<T> task = new Task<T>() {
            @Override
            protected T call() throws Exception {
                return action.call();
            }
        };
        task.setOnSucceeded(event -> done.accept(task.getValue()));
        task.setOnFailed(event -> failed.accept(task.getException()));
        Thread.startVirtualThread(task);
    }

    private static String errorText(Throwable error) {
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

    @FunctionalInterface
    private interface Fetch<T> {
        Page<T> get(int page) throws IOException, InterruptedException;
    }
}
