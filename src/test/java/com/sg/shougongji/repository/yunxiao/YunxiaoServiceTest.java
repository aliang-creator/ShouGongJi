package com.sg.shougongji.repository.yunxiao;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sg.shougongji.AppServices;
import com.sg.shougongji.model.Yunxiao.ApiException;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.model.Yunxiao.ChildDraft;
import com.sg.shougongji.model.Yunxiao.Choice;
import com.sg.shougongji.model.Yunxiao.Field;
import com.sg.shougongji.model.Yunxiao.Item;
import com.sg.shougongji.model.Yunxiao.Page;
import com.sg.shougongji.model.Yunxiao.PendingWrite;
import com.sg.shougongji.model.Yunxiao.WorkItemKind;
import com.sg.shougongji.model.Yunxiao.WorkItemStatus;
import com.sg.shougongji.model.YunxiaoWeek;
import com.sg.shougongji.repository.WorkLogDatabase;
import com.sg.shougongji.repository.YunxiaoRepository;
import com.sg.shougongji.service.TimerService;
import com.sg.shougongji.service.YunxiaoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class YunxiaoServiceTest {
    @TempDir
    Path directory;

    @Test
    void weeklyEffortReadsOnlyThisAccountsLinkedRecordsAndKeepsMultiDayRegistrationsSeparate() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoRepository repository = open();
        YunxiaoService sync = new YunxiaoService(repository, cloud.central());
        TimerService timer = servicesAt(Instant.parse("2026-11-12T03:00:00Z")).timer();
        List<Binding> bindings = List.of(new Binding(sync.scope(), "item-a", "项目 / A"),
                new Binding(sync.scope(), "item-a", "项目 / A"), new Binding(sync.scope(), "item-b", "项目 / B"),
                new Binding("another-organization", "other", "其他组织"));
        for (int index = 0; index < bindings.size(); index++) {
            timer.start("本地事项 " + index);
            repository.bind(timer.active().task().id(), bindings.get(index));
        }
        cloud.answerAt("GET", YunxiaoClient.CENTRAL_ENDPOINT + "/oapi/v1/platform/user",
                request -> response(request, 200, "{\"id\":\"my-account\",\"name\":\"本人\"}"));
        long thursday = LocalDate.of(2026, 11, 12).atStartOfDay(ZoneOffset.ofHours(8)).toInstant().toEpochMilli();
        cloud.answer("GET", "/workitems/item-a/effortRecords", """
                [{"id":"mine","owner":{"id":"my-account"},"actualTime":1.5,
                  "gmtStart":%d,"gmtEnd":%d,"workType":"开发","description":"联调"},
                 {"id":"mine","owner":{"id":"my-account"},"actualTime":1.5,"gmtStart":%d,"gmtEnd":%d},
                 {"id":"sunday","owner":{"id":"my-account"},"actualTime":0.25,
                  "gmtStart":"2026-11-15","gmtEnd":"2026-11-15"},
                 {"id":"theirs","owner":{"id":"colleague"},"creator":{"id":"my-account"},"actualTime":8,
                  "gmtStart":"2026-11-12","gmtEnd":"2026-11-12"},
                 {"id":"range","owner":{"id":"my-account"},"actualTime":9,
                  "gmtStart":"2026-11-08","gmtEnd":"2026-11-09"},
                 {"id":"next-week","owner":{"id":"my-account"},"actualTime":4,
                  "gmtStart":"2026-11-16","gmtEnd":"2026-11-16"},
                 {"id":"no-owner","actualTime":7,"gmtStart":"2026-11-12","gmtEnd":"2026-11-12"}]
                """.formatted(thursday, thursday, thursday, thursday));
        cloud.answer("GET", "/workitems/item-b/effortRecords", request -> response(request, 403, "{}"));

        YunxiaoWeek week = sync.weekEffort(LocalDate.of(2026, 11, 12));
        assertEquals("本人", week.accountName());
        assertEquals(2, week.workItemCount());
        assertEquals(1, week.unavailableItems().size());
        assertTrue(week.unavailableItems().getFirst().startsWith("项目 / B"));
        assertEquals(1, week.unreadableRecords());
        assertEquals(7, week.days().size());
        assertEquals(LocalDate.of(2026, 11, 9), week.days().getFirst().date());
        assertEquals(BigDecimal.ZERO, week.days().getFirst().hours());
        assertEquals(new BigDecimal("1.5"), week.days().get(3).hours());
        assertEquals("联调", week.days().get(3).records().getFirst().description());
        assertEquals(new BigDecimal("0.25"), week.days().getLast().hours());
        assertEquals(new BigDecimal("1.75"), week.dailyHours());
        assertEquals(1, week.multiDay().size());
        assertEquals(new BigDecimal("9"), week.multiDay().getFirst().hours());
        cloud.assertDone();
    }

    @Test
    void weeklyEffortReportsWhenTheTokenCannotReadItsAccount() throws Exception {
        Cloud cloud = new Cloud("https://projex.example.test/oapi/v1/projex");
        YunxiaoClient client = new YunxiaoClient("https://projex.example.test", "", "test-token", cloud::send);
        YunxiaoService sync = new YunxiaoService(open(), client);
        LocalDate day = LocalDate.of(2026, 11, 12);
        assertEquals(0, sync.weekEffort(day).workItemCount());
        bind(sync, task());
        cloud.answerAt("GET", "https://projex.example.test/oapi/v1/platform/user",
                request -> response(request, 403, "{}"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> sync.weekEffort(day));
        assertTrue(error.getMessage().contains("组织管理 → 用户"));
        cloud.assertDone();
    }

    @Test
    void readsCustomWorkflowStatesAndUpdatesTheLinkedItemByStatusId() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoService sync = new YunxiaoService(open(), cloud.central());
        Binding binding = bind(sync, task());
        String todo = """
                {"status":{"id":"todo","displayName":"待处理","name":"待办"}}
                """;
        String workflow = """
                {"statuses":[{"id":"todo","name":"待办"},
                             {"id":"doing-27","displayName":"处理中","name":"进行中"},
                             {"id":"review-86","name":"待审核"}]}
                """;
        Choice doing = new Choice("doing-27", "处理中");
        cloud.answer("GET", "/workitems/child-1", todo);
        cloud.answer("GET", "/workitems/child-1/workflow", workflow);
        WorkItemStatus status = sync.workItemStatus(binding);
        assertEquals(new Choice("todo", "待处理"), status.current());
        assertEquals(List.of(doing, new Choice("review-86", "待审核")), status.options());

        cloud.answer("GET", "/workitems/child-1", todo);
        cloud.answer("PUT", "/workitems/child-1", request -> {
            assertEquals(JsonParser.parseString("{\"status\":\"doing-27\"}"), body(request));
            return response(request, 204, "");
        });
        sync.updateStatus(binding, status.current(), doing);

        cloud.answer("GET", "/workitems/child-1", "{\"status\":{\"id\":\"doing-27\",\"displayName\":\"处理中\"}}");
        cloud.answer("GET", "/workitems/child-1/workflow", workflow);
        WorkItemStatus reloaded = sync.workItemStatus(binding);
        assertEquals(doing, reloaded.current());
        assertFalse(reloaded.options().contains(doing));
        cloud.assertDone();
    }

    @Test
    void checksTheCurrentStatusBeforeWritingAndSurfacesWorkflowRejections() throws Exception {
        Cloud cloud = new Cloud("https://projex.example.test/oapi/v1/projex");
        YunxiaoClient client = new YunxiaoClient("https://projex.example.test", "", "test-token", cloud::send);
        YunxiaoService sync = new YunxiaoService(open(), client);
        Binding binding = bind(sync, task());
        Choice todo = new Choice("todo", "待处理");
        Choice done = new Choice("done", "已完成");
        Binding anotherOrg = new Binding(new Cloud().central().scope(), binding.childId(), binding.label());
        assertThrows(IllegalArgumentException.class, () -> sync.workItemStatus(anotherOrg));
        assertThrows(IllegalArgumentException.class, () -> sync.updateStatus(anotherOrg, todo, done));

        cloud.answer("GET", "/workitems/child-1", "{\"status\":{\"id\":\"doing\",\"name\":\"处理中\"}}");
        assertThrows(IllegalArgumentException.class, () -> sync.updateStatus(binding, todo, done));
        cloud.answer("GET", "/workitems/child-1", "{\"status\":{\"id\":\"done\",\"name\":\"已完成\"}}");
        sync.updateStatus(binding, todo, done);
        cloud.assertDone();

        cloud.answer("GET", "/workitems/child-1", "{\"status\":{\"id\":\"todo\",\"name\":\"待处理\"}}");
        cloud.answer("PUT", "/workitems/child-1", request ->
                response(request, 403, "{\"message\":\"该流转需要填写审核人\"}"));
        ApiException rejection = assertThrows(ApiException.class, () -> sync.updateStatus(binding, todo, done));
        assertTrue(rejection.getMessage().contains("该流转需要填写审核人"));
        cloud.assertDone();
    }

    @Test
    void searchesSubtasksByTitleOrNumberWithinTheProject() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoService sync = new YunxiaoService(open(), cloud.central());
        String items = """
                [{"id":"task-12","subject":"导出超时","serialNumber":"PRJ-12","categoryId":"Task",
                  "space":{"id":"project-1"},"parentId":"req-1","workitemType":{"id":"task-type"}},
                 {"id":"task-11","subject":"导出任务","serialNumber":"PRJ-11","categoryId":"Task",
                  "space":{"id":"project-1"},"parentId":null}]
                """;
        cloud.answer("POST", "/workitems:search", request -> {
            JsonObject query = body(request);
            assertEquals("project-1", query.get("spaceId").getAsString());
            assertEquals("Task", query.get("category").getAsString());
            JsonObject filter = JsonParser.parseString(query.get("conditions").getAsString()).getAsJsonObject()
                    .getAsJsonArray("conditionGroups").get(0).getAsJsonArray().get(0).getAsJsonObject();
            assertEquals("subject", filter.get("fieldIdentifier").getAsString());
            assertEquals("导出", filter.getAsJsonArray("value").get(0).getAsString());
            return response(request, 200, items);
        });
        List<Item> matches = sync.timerItems("project-1", WorkItemKind.SUBTASK, " 导出 ", 1).items();
        assertEquals(1, matches.size());
        Item task = matches.getFirst();
        assertEquals("req-1", task.parentId());

        cloud.answer("POST", "/workitems:search", request -> new Response(request, 200, "[]",
                HttpHeaders.of(Map.of("x-next-page", List.of("2")), (name, value) -> true)));
        cloud.answer("POST", "/workitems:search", request -> {
            assertEquals(2, body(request).get("page").getAsInt());
            return response(request, 200, items);
        });
        assertEquals(new Page<>(List.of(task), 0), sync.timerItems("project-1", WorkItemKind.SUBTASK, "prj-12", 1));
        cloud.assertDone();
    }

    @Test
    void separatesRequirementsAndIndependentTasksAndSkipsPagesOfSubtasks() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoService sync = new YunxiaoService(open(), cloud.central());
        String subtask = """
                [{"id":"task-12","categoryId":"Task","space":{"id":"project-1"},"parentId":"req-1"}]
                """;
        cloud.answer("POST", "/workitems:search", request -> {
            assertEquals("Req", body(request).get("category").getAsString());
            return response(request, 200, """
                    [{"id":"req-1","categoryId":"Req","space":{"id":"project-1"}},
                     {"id":"req-2","categoryId":"Req","space":{"id":"other-project"}}]
                    """);
        });
        assertEquals(List.of("req-1"), sync.timerItems("project-1", WorkItemKind.REQUIREMENT, "", 1)
                .items().stream().map(Item::id).toList());

        cloud.answer("POST", "/workitems:search", request -> {
            JsonObject query = body(request);
            assertEquals("Task", query.get("category").getAsString());
            assertEquals(1, query.get("page").getAsInt());
            return new Response(request, 200, subtask,
                    HttpHeaders.of(Map.of("x-next-page", List.of("2")), (name, value) -> true));
        });
        cloud.answer("POST", "/workitems:search", request -> {
            assertEquals(2, body(request).get("page").getAsInt());
            return new Response(request, 200, """
                    [{"id":"task-10","categoryId":"Task","space":{"id":"project-1"}},
                     {"id":"task-11","categoryId":"Task","space":{"id":"project-1"},"parentId":"req-1"}]
                    """, HttpHeaders.of(Map.of("x-next-page", List.of("3")), (name, value) -> true));
        });
        Page<Item> independent = sync.timerItems("project-1", WorkItemKind.INDEPENDENT_TASK, "", 1);
        assertEquals(List.of("task-10"), independent.items().stream().map(Item::id).toList());
        assertEquals(3, independent.next());

        cloud.answer("POST", "/workitems:search", request -> {
            assertEquals(3, body(request).get("page").getAsInt());
            return response(request, 200, subtask);
        });
        assertEquals(new Page<Item>(List.of(), 0),
                sync.timerItems("project-1", WorkItemKind.INDEPENDENT_TASK, "", independent.next()));
        cloud.assertDone();
    }

    @Test
    void requiresAChildForRequirementsAndAllowsDirectTimingForExistingTasks() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoRepository repository = open();
        YunxiaoService sync = new YunxiaoService(repository, cloud.central());
        Choice project = new Choice("project-1", "项目");
        Item requirement = new Item("req-1", "PRJ-1", "需求", "req-type", project.id(), "Req", "", "");
        Item independent = new Item("task-1", "PRJ-2", "独立任务", "task-type", project.id(), "Task", "", "");
        Item subtask = new Item("child-1", "PRJ-3", "已有子任务", "task-type", project.id(), "Task", requirement.id(), "");

        assertThrows(IllegalArgumentException.class, () -> sync.timerTask(project, requirement));
        assertTrue(repository.findBindings().isEmpty());
        assertEquals(independent.id(), repository.findBinding(sync.timerTask(project, independent)).childId());
        assertEquals(subtask.id(), repository.findBinding(sync.timerTask(project, subtask)).childId());
        cloud.assertDone();
    }

    @Test
    void createsChildAfterFirstStopAndUpdatesTheSameDailyEffortAfterResuming() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoClient client = cloud.central();
        Choice project = new Choice("project-1", "项目");
        Item parent = new Item("task-1", "PRJ-1", "订单导出", "task-type", project.id(), "Task", "", "");
        Instant start = Instant.parse("2026-11-12T01:00:00Z");
        AppServices services = servicesAt(start);
        TimerService timer = services.timer();
        String title = "排查导出超时";
        timer.start(title);
        var local = timer.active().task();
        YunxiaoService sync = new YunxiaoService(open(), client);
        ChildDraft draft = new ChildDraft(project, parent, "task-type", title, "member-1", "", Map.of());
        long firstChild = sync.prepareAutomaticChild(draft);
        timer.start(firstChild);
        assertNotEquals(local.id(), firstChild);
        assertEquals(title, timer.active().task().title());
        assertNull(open().findBinding(firstChild));
        assertEquals(0, services.yunxiaoAutoSync().pendingCount());
        assertNull(services.yunxiaoAutoSync().sync(sync, null));
        cloud.assertDone();

        Instant firstEnd = start.plusSeconds(40 * 60);
        services = servicesAt(firstEnd);
        timer = services.timer();
        timer.pause(timer.active().id(), firstEnd, false);
        cloud.answer("POST", "/workitems", request -> {
            JsonObject requestBody = body(request);
            assertEquals(parent.id(), requestBody.get("parentId").getAsString());
            assertEquals(title, requestBody.get("subject").getAsString());
            assertEquals("", requestBody.get("description").getAsString());
            return response(request, 503, "{}");
        });
        var failure = services.yunxiaoAutoSync().sync(sync, null);
        assertInstanceOf(ApiException.class, failure.cause());
        assertEquals(1, services.yunxiaoAutoSync().pendingCount());
        assertNull(timer.active());

        cloud.answer("GET", "/workitems/task-1/relationRecords?relationType=SUB",
                "[{\"resourceType\":\"WORKITEM\",\"resourceId\":\"child-1\"}]");
        cloud.answer("GET", "/workitems/child-1", request -> response(request, 200, new Gson().toJson(Map.of(
                "id", "child-1", "categoryId", "Task", "parentId", parent.id(), "subject", title,
                "space", Map.of("id", project.id()), "description", ""))));
        YunxiaoService reopened = new YunxiaoService(open(), client);
        Instant secondStart = start.plusSeconds(3600);
        services = servicesAt(secondStart);
        assertInstanceOf(YunxiaoService.UncertainWrite.class, services.yunxiaoAutoSync().sync(reopened, null).cause());
        assertEquals(1, services.yunxiaoAutoSync().pendingCount());
        Item first = new Item("child-1", "PRJ-3", title, "task-type", project.id(), "Task", parent.id(), "");
        Item unrelated = new Item("other-1", "PRJ-4", title, "task-type", project.id(), "Task", "wrong-parent", "");
        assertThrows(IllegalArgumentException.class, () -> reopened.bindAutomaticChild(firstChild, unrelated));
        reopened.bindAutomaticChild(firstChild, first);
        cloud.answer("POST", "/workitems/child-1/effortRecords", request -> {
            assertEquals(new BigDecimal("0.67"), body(request).get("actualTime").getAsBigDecimal());
            return response(request, 201, "{\"id\":\"effort-1\"}");
        });
        assertNull(services.yunxiaoAutoSync().sync(reopened, null));
        assertEquals(0, services.yunxiaoAutoSync().pendingCount());
        assertEquals("child-1", open().findBinding(firstChild).childId());

        Item otherParent = new Item("req-2", "PRJ-2", "批量导出", "req-type", project.id(), "Req", "", "");
        long secondChild = reopened.prepareAutomaticChild(
                new ChildDraft(project, otherParent, "task-type", title, "member-1", "", Map.of()));
        assertNotEquals(firstChild, secondChild);
        assertEquals(firstChild, reopened.timerTask(project, first));
        assertEquals(firstChild, reopened.prepareAutomaticChild(draft));
        services.timer().start(firstChild);
        Instant secondEnd = secondStart.plusSeconds(20 * 60);
        services = servicesAt(secondEnd);
        services.timer().pause(services.timer().active().id(), secondEnd, false);
        cloud.answer("PUT", "/workitems/child-1/effortRecords/effort-1", request -> {
            assertEquals(new BigDecimal("1.00"), body(request).get("actualTime").getAsBigDecimal());
            return response(request, 204, "");
        });
        assertNull(services.yunxiaoAutoSync().sync(reopened, null));
        assertEquals(0, services.yunxiaoAutoSync().pendingCount());
        assertNull(open().findBinding(secondChild));
        cloud.assertDone();
    }

    @Test
    void switchingAfterMidnightQueuesSeparateDaysForTheOriginalOrganization() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoService sync = new YunxiaoService(open(), cloud.central());
        Choice project = new Choice("project-1", "项目");
        Item parent = new Item("req-1", "PRJ-1", "订单导出", "req-type", project.id(), "Req", "", "");
        long taskId = sync.prepareAutomaticChild(
                new ChildDraft(project, parent, "task-type", "修复导出", "member-1", " \n", Map.of()));
        Instant start = Instant.parse("2026-11-12T23:45:00Z");
        AppServices services = servicesAt(start);
        services.timer().start(taskId);
        services.timer().pause(services.timer().active().id(), start, false);
        assertEquals(0, services.yunxiaoAutoSync().pendingCount());
        services.timer().start(taskId);
        services = servicesAt(start.plusSeconds(30 * 60));
        services.timer().start("本地事项");
        assertEquals(2, services.yunxiaoAutoSync().pendingCount());
        YunxiaoService wrongOrganization = new YunxiaoService(open(), new YunxiaoClient("", "org-2", "test-token",
                request -> fail("Queued work must stay in its original organization")));
        assertNull(services.yunxiaoAutoSync().sync(wrongOrganization, null));
        assertEquals(2, services.yunxiaoAutoSync().pendingCount());

        cloud.answer("POST", "/workitems", request -> {
            assertEquals("", body(request).get("description").getAsString());
            return response(request, 201, "{\"id\":\"child-1\"}");
        });
        for (String day : List.of("2026-11-12", "2026-11-13")) {
            cloud.answer("POST", "/workitems/child-1/effortRecords", request -> {
                JsonObject effort = body(request);
                assertEquals(day, effort.get("gmtStart").getAsString());
                assertEquals(day, effort.get("gmtEnd").getAsString());
                assertEquals(new BigDecimal("0.25"), effort.get("actualTime").getAsBigDecimal());
                return response(request, 201, "{\"id\":\"effort-" + day + "\"}");
            });
        }
        assertNull(services.yunxiaoAutoSync().sync(sync, null));
        assertEquals(0, services.yunxiaoAutoSync().pendingCount());
        assertEquals("本地事项", services.timer().active().task().title());
        cloud.assertDone();
    }

    @Test
    void finishingAnotherSegmentDuringUploadKeepsTheNewTotalQueued() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoService sync = new YunxiaoService(open(), cloud.central());
        Choice project = new Choice("project-1", "项目");
        Item parent = new Item("req-1", "PRJ-1", "订单导出", "req-type", project.id(), "Req", "", "");
        long taskId = sync.prepareAutomaticChild(
                new ChildDraft(project, parent, "task-type", "修复导出", "member-1", "", Map.of()));
        Instant start = Instant.parse("2026-11-12T01:00:00Z");
        servicesAt(start).timer().start(taskId);
        Instant end = start.plusSeconds(15 * 60);
        AppServices services = servicesAt(end);
        services.timer().pause(services.timer().active().id(), end, false);

        cloud.answer("POST", "/workitems", "{\"id\":\"child-1\"}");
        cloud.answer("POST", "/workitems/child-1/effortRecords", request -> {
            assertEquals(new BigDecimal("0.25"), body(request).get("actualTime").getAsBigDecimal());
            assertDoesNotThrow(() -> {
                servicesAt(end).timer().start(taskId);
                Instant secondEnd = end.plusSeconds(30 * 60);
                TimerService timer = servicesAt(secondEnd).timer();
                timer.pause(timer.active().id(), secondEnd, false);
            });
            return response(request, 201, "{\"id\":\"effort-1\"}");
        });
        assertNull(services.yunxiaoAutoSync().sync(sync, null));
        assertEquals(1, services.yunxiaoAutoSync().pendingCount());

        cloud.answer("PUT", "/workitems/child-1/effortRecords/effort-1", request -> {
            assertEquals(new BigDecimal("0.75"), body(request).get("actualTime").getAsBigDecimal());
            return response(request, 204, "");
        });
        assertNull(services.yunxiaoAutoSync().sync(sync, null));
        assertEquals(0, services.yunxiaoAutoSync().pendingCount());
        cloud.assertDone();
    }

    @Test
    void createsChildInSelectedProjectAndUpdatesTheSameDailyEffort() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoClient client = cloud.central();
        YunxiaoRepository repository = open();
        long task = task();

        cloud.answer("POST", "/projects:search", request -> {
            assertEquals(1, body(request).get("page").getAsInt());
            return new Response(request, 200, "[{\"id\":\"project-1\",\"name\":\"项目一\"}]",
                    HttpHeaders.of(Map.of("x-next-page", List.of("2")), (name, value) -> true));
        });
        Page<Choice> page = client.projects(1);
        assertEquals(2, page.next());
        Choice project = page.items().getFirst();

        cloud.answer("POST", "/workitems:search", request -> {
            assertEquals("Req", body(request).get("category").getAsString());
            assertEquals(project.id(), body(request).get("spaceId").getAsString());
            return response(request, 200, """
                    [{"id":"req-1","subject":"需求","serialNumber":"PRJ-1","categoryId":"Req",
                      "space":{"id":"project-1"},"workitemType":{"id":"req-type"}}]
                    """);
        });
        Item parent = client.requirements(project.id(), 1).items().getFirst();
        cloud.answer("GET", "/workitemTypes/req-type/relationWorkitemTypes?relationType=SUB",
                """
                [{"relationType":"SUB","workitemTypeList":[{"id":"allowed"}]}]
                """);
        cloud.answer("GET", "/projects/project-1/workitemTypes?category=Task",
                """
                [{"id":"allowed","name":"开发任务","enable":true},{"id":"other","name":"其他","enable":true}]
                """);
        assertEquals(List.of(new Choice("allowed", "开发任务")),
                client.taskTypes(project.id(), parent.typeId()));

        LocalDate date = LocalDate.of(2026, 11, 7);
        String description = "## 验收\n核对工时  \n\n";
        cloud.answer("POST", "/workitems", request -> {
            JsonObject body = body(request);
            assertEquals(project.id(), body.get("spaceId").getAsString());
            assertEquals(parent.id(), body.get("parentId").getAsString());
            assertEquals("allowed", body.get("workitemTypeId").getAsString());
            assertEquals("member-1", body.get("assignedTo").getAsString());
            assertEquals(description, body.get("description").getAsString());
            assertEquals("p1", body.getAsJsonObject("customFieldValues").get("priority").getAsString());
            assertEquals(date + " 00:00:00", body.getAsJsonObject("customFieldValues").get("79").getAsString());
            assertEquals(date + " 23:59:59", body.getAsJsonObject("customFieldValues").get("80").getAsString());
            return response(request, 201, "{\"id\":\"child-1\"}");
        });
        YunxiaoService sync = new YunxiaoService(repository, client);
        Field start = new Field("79", "计划开始时间", "SystemCustomField", "date", "", false, List.of());
        Field end = new Field("80", "计划完成时间", "SystemCustomField", "date", "", false, List.of());
        Binding binding = sync.create(task, project, parent, "allowed", "本地事项", "member-1", description,
                Map.of("priority", "p1", start.id(), start.plannedDate(date), end.id(), end.plannedDate(date)), false);
        assertEquals(binding, open().findBindings().get(task));

        cloud.answer("POST", "/workitems/child-1/effortRecords", request -> {
            JsonObject body = body(request);
            assertEquals(new BigDecimal("1.5"), body.get("actualTime").getAsBigDecimal());
            assertEquals(date.toString(), body.get("gmtStart").getAsString());
            assertEquals(date.toString(), body.get("gmtEnd").getAsString());
            assertEquals(new JsonPrimitive(true), body.get("containsRestDay"));
            assertFalse(body.has("operatorId"));
            return response(request, 201, "{\"id\":\"effort-1\"}");
        });
        cloud.answer("GET", "/workitems/child-1", scheduledItem(date));
        cloud.answer("POST", "/workitems/child-1/estimatedEfforts", request -> {
            JsonObject body = body(request);
            assertEquals(new BigDecimal("1.5"), body.get("spentTime").getAsBigDecimal());
            assertEquals("member-1", body.get("owner").getAsString());
            return response(request, 201, "{\"id\":\"estimate-1\"}");
        });
        sync.submit(task, binding, date, new BigDecimal("1.5"), false);
        cloud.answer("PUT", "/workitems/child-1/effortRecords/effort-1", request -> {
            JsonObject body = body(request);
            assertEquals(new BigDecimal("2"), body.get("actualTime").getAsBigDecimal());
            assertEquals(new JsonPrimitive(true), body.get("containsRestDay"));
            return response(request, 204, "");
        });
        cloud.answer("GET", "/workitems/child-1", scheduledItem(date));
        cloud.answer("PUT", "/workitems/child-1/estimatedEfforts/estimate-1", request -> {
            assertEquals(new BigDecimal("2"), body(request).get("spentTime").getAsBigDecimal());
            return response(request, 204, "");
        });
        new YunxiaoService(open(), client).submit(task, binding, date, new BigDecimal("2"), false);
        cloud.assertDone();
    }

    @Test
    void recoversAnIndependentTaskCreatedWithALegacyMarker() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoClient client = cloud.central();
        YunxiaoRepository repository = open();
        long task = task();
        Choice project = new Choice("project-1", "项目一");
        YunxiaoService sync = new YunxiaoService(repository, client);

        cloud.answer("GET", "/projects/project-1/workitemTypes?category=Task", """
                [{"id":"other","name":"其他","enable":true},
                 {"id":"task-type","name":"开发任务","enable":true,"defaultType":true},
                 {"id":"disabled","enable":false,"defaultType":true}]
                """);
        assertEquals(List.of(new Choice("task-type", "开发任务"), new Choice("other", "其他")),
                client.taskTypes(project.id()));
        String description = "## 验收\n核对工时";
        PendingWrite legacy = repository.reserveWrite(task, sync.scope(), "project-task:" + project.id());
        String legacyDescription = description + "\n\n" + legacy.marker();

        cloud.answer("POST", "/workitems:search", request -> {
            JsonObject body = body(request);
            assertEquals("Task", body.get("category").getAsString());
            assertEquals(project.id(), body.get("spaceId").getAsString());
            assertEquals(1, body.get("page").getAsInt());
            Map<String, Object> nestedTask = Map.of("id", "nested", "parentId", "req", "categoryId", "Task",
                    "space", Map.of("id", project.id()), "description", legacyDescription);
            return new Response(request, 200, new Gson().toJson(List.of(nestedTask)),
                    HttpHeaders.of(Map.of("x-next-page", List.of("2")), (name, value) -> true));
        });
        cloud.answer("POST", "/workitems:search", request -> {
            assertEquals(2, body(request).get("page").getAsInt());
            Map<String, Object> independentTask = Map.of("id", "independent", "categoryId", "Task",
                    "space", Map.of("id", project.id()), "description", legacyDescription);
            return response(request, 200, new Gson().toJson(List.of(independentTask)));
        });
        Binding binding = new YunxiaoService(open(), client).create(task, project, null, "task-type", "独立任务",
                "member-1", description, Map.of("priority", "p2"), false);
        assertEquals("independent", binding.childId());
        assertEquals("项目一 / 独立任务", binding.label());

        cloud.answer("POST", "/workitems/independent/effortRecords", "{\"id\":\"effort-1\"}");
        LocalDate day = LocalDate.of(2026, 11, 7);
        cloud.answer("GET", "/workitems/independent", "{\"assignedTo\":{\"id\":\"member-1\"}}");
        cloud.answer("PUT", "/workitems/independent", request -> {
            JsonObject body = body(request);
            assertEquals(day + " 00:00:00", body.get("79").getAsString());
            assertEquals(day + " 23:59:59", body.get("80").getAsString());
            return response(request, 204, "");
        });
        ArrayDeque<String> estimatedDescriptions = new ArrayDeque<>();
        cloud.answer("POST", "/workitems/independent/estimatedEfforts", request -> {
            estimatedDescriptions.add(body(request).get("description").getAsString());
            return response(request, 503, "{}");
        });
        YunxiaoService.PartialSubmission partial = assertThrows(YunxiaoService.PartialSubmission.class,
                () -> sync.submit(task, binding, day, BigDecimal.ONE, false));
        assertInstanceOf(ApiException.class, partial.getCause());

        cloud.answer("PUT", "/workitems/independent/effortRecords/effort-1",
                request -> response(request, 204, ""));
        cloud.answer("GET", "/workitems/independent", scheduledItem(day));
        cloud.answer("GET", "/workitems/independent/estimatedEfforts", new Gson().toJson(List.of(Map.of(
                "id", "estimate-1", "description", estimatedDescriptions.getFirst()))));
        cloud.answer("PUT", "/workitems/independent/estimatedEfforts/estimate-1", request -> {
            assertEquals(BigDecimal.ONE, body(request).get("spentTime").getAsBigDecimal());
            return response(request, 204, "");
        });
        new YunxiaoService(open(), client).submit(task, binding, day, BigDecimal.ONE, false);
        cloud.assertDone();
    }

    @Test
    void sendsThePriorityOptionIdFromTheProjectTemplate() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoClient client = cloud.central();
        cloud.answer("GET", "/projects/project-1/workitemTypes/task-type/fields", """
                [{"id":"priority","name":"优先级","type":"SystemCustomField","format":"list",
                  "required":true,"defaultValue":"priority-medium",
                  "options":[{"id":"priority-high","value":"高","displayValue":"高"},
                             {"id":"priority-medium","value":"中","displayValue":"中"}]},
                 {"id":"custom-list","name":"自定义选项","type":"CustomField","format":"list",
                  "options":[{"id":"option-1","value":"configured-value","displayValue":"选项"}]}]
                """);
        List<Field> fields = client.creationFields("project-1", "task-type");
        Field priority = fields.getFirst();
        assertEquals(List.of(new Choice("priority-high", "高"),
                new Choice("priority-medium", "中")), priority.options());
        assertEquals(priority.defaultValue(), priority.options().get(1).id());
        assertEquals("configured-value", fields.getLast().options().getFirst().id());

        cloud.answer("POST", "/workitems", request -> {
            assertEquals("priority-high", body(request).getAsJsonObject("customFieldValues")
                    .get("priority").getAsString());
            return response(request, 201, "{\"id\":\"created-task\"}");
        });
        assertEquals("created-task", client.createTask("project-1", null, "task-type", "任务标题",
                "member-1", "", Map.of(priority.id(), priority.options().getFirst().id())));
        cloud.assertDone();
    }

    @Test
    void reconcilesAnUnconfirmedCreateAfterReopeningWithoutAnotherPost() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoClient client = cloud.central();
        YunxiaoRepository repository = open();
        long task = task();
        YunxiaoService sync = new YunxiaoService(repository, client);
        Binding binding = bind(sync, task);
        LocalDate day = LocalDate.of(2026, 11, 7);
        ArrayDeque<String> descriptions = new ArrayDeque<>();
        cloud.answer("POST", "/workitems/child-1/effortRecords", request -> {
            descriptions.add(body(request).get("description").getAsString());
            return response(request, 503, "{}");
        });
        assertThrows(ApiException.class, () -> sync.submit(task, binding, day, BigDecimal.ONE, false));

        YunxiaoService reopened = new YunxiaoService(open(), client);
        cloud.answer("GET", "/workitems/child-1/effortRecords", "[]");
        assertThrows(YunxiaoService.UncertainWrite.class, () -> reopened.submit(task, binding, day, BigDecimal.ONE, false));
        cloud.answer("GET", "/workitems/child-1/effortRecords", new Gson().toJson(List.of(Map.of(
                "id", "recovered", "description", descriptions.getFirst()))));
        cloud.answer("PUT", "/workitems/child-1/effortRecords/recovered", request -> response(request, 204, ""));
        reopened.submit(task, binding, day, BigDecimal.ONE, false);
        cloud.assertDone();
    }

    @Test
    void confirmsARestDayEditWithoutUpdatingActualHoursAgain() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoClient client = cloud.central();
        YunxiaoRepository repository = open();
        long task = task();
        YunxiaoService sync = new YunxiaoService(repository, client);
        Choice project = new Choice("project-1", "项目");
        cloud.answer("POST", "/workitems", "{\"id\":\"child-1\"}");
        Binding binding = sync.create(task, project, null, "task", "本地事项", "member-1", Map.of(), false);
        LocalDate day = LocalDate.of(2026, 11, 7);
        BigDecimal hours = new BigDecimal("0.16");
        PendingWrite write = repository.reserveWrite(task, client.scope(), "effort:child-1:" + day);
        repository.completeWrite(write.marker(), "existing");

        cloud.answer("PUT", "/workitems/child-1/effortRecords/existing", YunxiaoServiceTest::restDayRejection);
        cloud.answer("GET", "/workitems/child-1/effortRecords", """
                [{"id":"existing","gmtStart":1793980800000,"gmtEnd":1793980800000,"actualTime":0.2}]
                """);
        YunxiaoService.ManualEffortRequired rejection = assertThrows(YunxiaoService.ManualEffortRequired.class,
                () -> sync.submit(task, binding, day, hours, false));
        assertEquals("", rejection.newRecordDescription());

        cloud.answer("GET", "/workitems/child-1/effortRecords", """
                [{"id":"other","gmtStart":"2026-11-07","gmtEnd":"2026-11-07","actualTime":0.16},
                 {"id":"existing","gmtStart":"2026-11-08","gmtEnd":"2026-11-08","actualTime":0.16}]
                """);
        assertThrows(IllegalArgumentException.class, () -> sync.confirmWebEffort(task, binding, day, hours, false));

        String savedEffort = """
                [{"id":"existing","gmtStart":1793980800000,"gmtEnd":1793980800000,"actualTime":0.160}]
                """;
        cloud.answer("GET", "/workitems/child-1/effortRecords", savedEffort);
        cloud.answer("GET", "/workitems/child-1", scheduledItem(day));
        cloud.answer("POST", "/workitems/child-1/estimatedEfforts", request -> {
            assertEquals(hours, body(request).get("spentTime").getAsBigDecimal());
            return response(request, 201, "{\"id\":\"estimate-1\"}");
        });
        sync.confirmWebEffort(task, binding, day, hours, false);

        cloud.answer("PUT", "/workitems/child-1/effortRecords/existing", YunxiaoServiceTest::restDayRejection);
        cloud.answer("GET", "/workitems/child-1/effortRecords", savedEffort);
        cloud.answer("GET", "/workitems/child-1", scheduledItem(day));
        cloud.answer("PUT", "/workitems/child-1/estimatedEfforts/estimate-1", request -> response(request, 204, ""));
        sync.submit(task, binding, day, hours, false);
        cloud.assertDone();
    }

    @Test
    void adoptsANewRestDayRecordOnlyWithTheRegistrationMarker() throws Exception {
        Cloud cloud = new Cloud();
        YunxiaoClient client = cloud.central();
        YunxiaoRepository repository = open();
        long task = task();
        YunxiaoService sync = new YunxiaoService(repository, client);
        Binding binding = bind(sync, task);
        LocalDate day = LocalDate.of(2026, 11, 7);
        cloud.answer("POST", "/workitems/child-1/effortRecords", YunxiaoServiceTest::restDayRejection);
        cloud.answer("GET", "/workitems/child-1/effortRecords", "[]");
        YunxiaoService.ManualEffortRequired rejection = assertThrows(YunxiaoService.ManualEffortRequired.class,
                () -> sync.submit(task, binding, day, BigDecimal.ONE, false));
        JsonObject record = new JsonObject();
        record.addProperty("id", "registered-in-web");
        record.addProperty("gmtStart", day.toString());
        record.addProperty("gmtEnd", day.toString());
        record.addProperty("actualTime", 1);
        cloud.answer("GET", "/workitems/child-1/effortRecords", new Gson().toJson(List.of(record)));
        assertThrows(IllegalArgumentException.class,
                () -> sync.confirmWebEffort(task, binding, day, BigDecimal.ONE, false));
        record.addProperty("description", rejection.newRecordDescription());
        cloud.answer("GET", "/workitems/child-1/effortRecords", new Gson().toJson(List.of(record)));
        new YunxiaoService(open(), client).confirmWebEffort(task, binding, day, BigDecimal.ONE, false);
        assertEquals("registered-in-web", repository.reserveWrite(task, client.scope(), "effort:child-1:" + day).remoteId());
        cloud.assertDone();
    }

    @Test
    void regionUsesItsOwnScopeAndKnownRejectionCanBeRetried() throws Exception {
        Cloud cloud = new Cloud("https://projex.example.test/oapi/v1/projex");
        YunxiaoClient client = new YunxiaoClient("https://projex.example.test/", "", "test-token", cloud::send);
        YunxiaoRepository repository = open();
        long task = task();
        YunxiaoService sync = new YunxiaoService(repository, client);
        Binding binding = bind(sync, task);
        LocalDate day = LocalDate.of(2026, 11, 7);
        assertThrows(IllegalArgumentException.class,
                () -> new YunxiaoService(repository, new Cloud().central()).submit(task, binding, day, BigDecimal.ONE, false));
        cloud.answer("POST", "/workitems/child-1/effortRecords",
                request -> response(request, 403, "{\"message\":\"check test-token\"}"));
        ApiException rejection = assertThrows(ApiException.class,
                () -> sync.submit(task, binding, day, BigDecimal.ONE, false));
        assertFalse(rejection.getMessage().contains("test-token"));
        cloud.answer("POST", "/workitems/child-1/effortRecords", "{\"id\":\"effort-1\"}");
        sync.submit(task, binding, day, BigDecimal.ONE, false);
        cloud.assertDone();
    }

    private YunxiaoRepository open() throws Exception {
        return new YunxiaoRepository(new WorkLogDatabase(database()));
    }

    private static String scheduledItem(LocalDate day) {
        return """
                {"assignedTo":{"id":"member-1"},"customFieldValues":[
                  {"fieldId":"79","values":[{"identifier":"%s 00:00:00"}]},
                  {"fieldId":"80","values":[{"identifier":"%s 23:59:59"}]}]}
                """.formatted(day, day);
    }

    private static Response restDayRejection(HttpRequest request) {
        assertEquals(new JsonPrimitive(true), body(request).get("containsRestDay"));
        return response(request, 400, "{\"message\":\"填的时间段包含休息日，需要设置是否包含休息日\"}");
    }

    private Path database() {
        return directory.resolve("worklog.sqlite");
    }

    private AppServices servicesAt(Instant instant) throws Exception {
        return AppServices.open(database(), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private long task() throws Exception {
        TimerService timer = AppServices.open(database(), Clock.systemUTC()).timer();
        timer.start("本地事项");
        return timer.active().task().id();
    }

    private static Binding bind(YunxiaoService sync, long task) throws Exception {
        Choice project = new Choice("project-1", "项目");
        Item parent = new Item("req-1", "PRJ-1", "需求", "req-type", project.id(), "Req", "", "");
        Item child = new Item("child-1", "PRJ-2", "子任务", "task", project.id(), "Task", parent.id(), "");
        return sync.bind(task, project, parent, child);
    }

    private static JsonObject body(HttpRequest request) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override public void onNext(ByteBuffer bytes) {
                byte[] chunk = new byte[bytes.remaining()];
                bytes.get(chunk);
                buffer.writeBytes(chunk);
            }

            @Override public void onError(Throwable failure) {
                result.completeExceptionally(failure);
            }

            @Override public void onComplete() {
                result.complete(JsonParser.parseString(buffer.toString(StandardCharsets.UTF_8)).getAsJsonObject());
            }
        });
        return result.join();
    }

    private static Response response(HttpRequest request, int status, String body) {
        return new Response(request, status, body, HttpHeaders.of(Map.of(), (name, value) -> true));
    }

    private record Response(HttpRequest request, int statusCode, String body, HttpHeaders headers)
            implements HttpResponse<String> {
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
    }

    private static final class Cloud {
        private final String base;
        private final Queue<Function<HttpRequest, Response>> calls = new ArrayDeque<>();

        Cloud() {
            this(YunxiaoClient.CENTRAL_ENDPOINT + "/oapi/v1/projex/organizations/org-1");
        }

        Cloud(String base) {
            this.base = base;
        }

        YunxiaoClient central() {
            return new YunxiaoClient("", "org-1", "test-token", this::send);
        }

        void answer(String method, String path, String data) {
            answer(method, path, request -> response(request, 200, data));
        }

        void answer(String method, String path, Function<HttpRequest, Response> response) {
            answerAt(method, base + path, response);
        }

        void answerAt(String method, String url, Function<HttpRequest, Response> response) {
            calls.add(request -> {
                assertEquals(method, request.method());
                assertEquals(url, request.uri().toString());
                assertEquals("test-token", request.headers().firstValue("x-yunxiao-token").orElseThrow());
                return response.apply(request);
            });
        }

        HttpResponse<String> send(HttpRequest request) {
            assertFalse(calls.isEmpty(), () -> "Unexpected request " + request.method() + " " + request.uri());
            return calls.remove().apply(request);
        }

        void assertDone() {
            assertTrue(calls.isEmpty());
        }
    }
}
