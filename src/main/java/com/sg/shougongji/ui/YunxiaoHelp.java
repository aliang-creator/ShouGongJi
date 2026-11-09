package com.sg.shougongji.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.function.Consumer;

import static com.sg.shougongji.ui.UiSupport.label;
import static com.sg.shougongji.ui.UiSupport.styleDialog;

final class YunxiaoHelp {
    private static final String DOCS = "https://help.aliyun.com/zh/yunxiao/";

    private YunxiaoHelp() {}

    static void show(Window owner, Consumer<String> openPage) {
        Tab setup = page("获取连接信息",
                section("中心版 · 组织 ID",
                        "登录云效并切换到要连接的组织。点右上角头像 → 管理后台 → 基本信息，复制「组织 ID」。"
                                + "看不到管理后台时，请让组织管理员提供 ID。回到收工记填入组织 ID，接入地址留空。",
                        link("阿里云文档 · 查找组织 ID", "user-guide/new-standard-enterprise", openPage)),
                section("Region 版 · 接入地址",
                        "打开阿里云云效控制台，选实例所在地域，在实例管理中打开目标实例。"
                                + "到「概览」的「实例信息」复制本机可访问的公网或专网访问域名。"
                                + "在收工记填入 HTTPS 实例地址，例如 https://devops.example.com，组织 ID 留空。",
                        link("阿里云文档 · 实例接入点", "developer-reference/service-access-point-domain", openPage)),
                section("中心版和 Region 版 · 个人访问令牌",
                        "在对应的云效工作台点右上角头像 → 个人设置 → 个人访问令牌 → 新建访问令牌。"
                                + "填写名称和有效期，按下方说明选择权限。新建后立即复制令牌，粘贴到收工记。",
                        link("阿里云文档 · 创建个人访问令牌", "user-guide/personal-access-token", openPage)),
                section("令牌权限",
                        "在「项目协作」中，给项目、项目成员、工作项类型、工作项类型字段配置和工作项相关项开通只读权限。"
                                + "新建任务和提交工时还需要工作项、实际工时和预计工时的读写权限。"
                                + "创建令牌的账号也需要能访问目标项目。"),
                section("保存连接",
                        "填好后点「使用此连接」。已保存且连接不变时，令牌留空即可沿用。"
                                + "令牌过期或切换组织、实例时，请填入新令牌。"));
        Tab usage = page("功能使用",
                section("1. 找到要同步的事项",
                        "结束计时后打开「收工页 → 事项汇总」，选中计时日期并展开事项。"),
                section("2. 新建或关联任务",
                        "云效还没有任务时，点「新建云效任务…」，选归属项目、工作项类型和负责人，再点「新建并关联」。"
                                + "要建到某个需求下面，勾选「添加父需求」并选需求。\n"
                                + "云效已有任务时，点「关联已有云效任务…」，选择项目和任务归属，选中对应任务后点「关联所选任务」。"),
                section("3. 设置默认负责人",
                        "可在「云效设置 → 默认负责人…」里选项目并按姓名搜索。新建时也可以点负责人旁的「设置默认…」。"
                                + "保存后，包含该成员的项目会自动选中此人。下拉框临时换人只影响当前任务。"),
                section("4. 核对并提交工时",
                        "打开已关联事项的「云效工时…」，核对当天的小时数，再点「提交工时」。"
                                + "同一事项同一天重复提交会更新收工记的原登记。"
                                + "收工记创建的任务按计时当天填写计划开始和完成日期，预计工时取提交的实际工时。"),
                section("休息日登记",
                        "如果云效要求在网页登记，按工时窗口的提示核对当天记录，勾选「包含休息日」并保存。"
                                + "回到收工记填入网页保存的小时数，点「核对网页工时」。"));

        TabPane tabs = new TabPane(setup, usage);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("yunxiao-help");
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("云效帮助");
        dialog.setHeaderText("云效连接与使用");
        dialog.setResizable(true);
        styleDialog(dialog, 590);
        dialog.getDialogPane().setContent(tabs);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("返回设置", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.showAndWait();
    }

    private static Tab page(String title, Node... sections) {
        VBox content = new VBox(16, sections);
        content.setPadding(new Insets(4, 14, 4, 0));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(365);
        return new Tab(title, scroll);
    }

    private static VBox section(String title, String body, Node... references) {
        VBox section = new VBox(5, label(title, "section-title"), label(body, "wrap"));
        section.getChildren().addAll(references);
        return section;
    }

    private static Hyperlink link(String title, String path, Consumer<String> openPage) {
        Hyperlink link = new Hyperlink(title);
        link.setOnAction(event -> openPage.accept(DOCS + path));
        return link;
    }
}
