package com.sg.shougongji.repository.yunxiao;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sg.shougongji.AppServices;
import com.sg.shougongji.model.Yunxiao.ApiException;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.model.Yunxiao.Choice;
import com.sg.shougongji.model.Yunxiao.Field;
import com.sg.shougongji.model.Yunxiao.Item;
import com.sg.shougongji.model.Yunxiao.Page;
import com.sg.shougongji.model.Yunxiao.PendingWrite;
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
import java.time.LocalDate;
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
        cloud.answer("POST", "/workitems", request -> {
            JsonObject body = body(request);
            assertEquals(project.id(), body.get("spaceId").getAsString());
            assertEquals(parent.id(), body.get("parentId").getAsString());
            assertEquals("allowed", body.get("workitemTypeId").getAsString());
            assertEquals("member-1", body.get("assignedTo").getAsString());
            assertEquals("p1", body.getAsJsonObject("customFieldValues").get("priority").getAsString());
            assertEquals(date + " 00:00:00", body.getAsJsonObject("customFieldValues").get("79").getAsString());
            assertEquals(date + " 23:59:59", body.getAsJsonObject("customFieldValues").get("80").getAsString());
            return response(request, 201, "{\"id\":\"child-1\"}");
        });
        YunxiaoService sync = new YunxiaoService(repository, client);
        Field start = new Field("79", "计划开始时间", "SystemCustomField", "date", "", false, List.of());
        Field end = new Field("80", "计划完成时间", "SystemCustomField", "date", "", false, List.of());
        Binding binding = sync.create(task, project, parent, "allowed", "本地事项", "member-1",
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
    void createsAnIndependentTaskAndRecoversItFromTheSelectedProject() throws Exception {
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
        ArrayDeque<String> descriptions = new ArrayDeque<>();
        cloud.answer("POST", "/workitems", request -> {
            JsonObject body = body(request);
            assertEquals(project.id(), body.get("spaceId").getAsString());
            assertFalse(body.has("parentId"));
            assertEquals("MARKDOWN", body.get("formatType").getAsString());
            assertEquals("p2", body.getAsJsonObject("customFieldValues").get("priority").getAsString());
            String sentDescription = body.get("description").getAsString();
            assertTrue(sentDescription.startsWith(description + "\n\n收工记#"));
            descriptions.add(sentDescription);
            return response(request, 503, "{}");
        });
        assertThrows(ApiException.class,
                () -> sync.create(task, project, null, "task-type", "独立任务", "member-1", description,
                        Map.of("priority", "p2"), false));

        cloud.answer("POST", "/workitems:search", request -> {
            JsonObject body = body(request);
            assertEquals("Task", body.get("category").getAsString());
            assertEquals(project.id(), body.get("spaceId").getAsString());
            assertEquals(1, body.get("page").getAsInt());
            Map<String, Object> nestedTask = Map.of("id", "nested", "parentId", "req", "categoryId", "Task",
                    "space", Map.of("id", project.id()), "description", descriptions.getFirst());
            return new Response(request, 200, new Gson().toJson(List.of(nestedTask)),
                    HttpHeaders.of(Map.of("x-next-page", List.of("2")), (name, value) -> true));
        });
        cloud.answer("POST", "/workitems:search", request -> {
            assertEquals(2, body(request).get("page").getAsInt());
            Map<String, Object> independentTask = Map.of("id", "independent", "categoryId", "Task",
                    "space", Map.of("id", project.id()), "description", descriptions.getFirst());
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
            calls.add(request -> {
                assertEquals(method, request.method());
                assertEquals(base + path, request.uri().toString());
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
