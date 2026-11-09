package com.sg.shougongji.repository.yunxiao;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sg.shougongji.model.Yunxiao.ApiException;
import com.sg.shougongji.model.Yunxiao.Choice;
import com.sg.shougongji.model.Yunxiao.Effort;
import com.sg.shougongji.model.Yunxiao.Field;
import com.sg.shougongji.model.Yunxiao.Item;
import com.sg.shougongji.model.Yunxiao.Page;
import com.sg.shougongji.model.Yunxiao.Planning;
import com.sg.shougongji.model.Yunxiao.ProtocolException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.sg.shougongji.model.Yunxiao.PLAN_END_FIELD;
import static com.sg.shougongji.model.Yunxiao.PLAN_START_FIELD;

public final class YunxiaoClient {
    public static final String CENTRAL_ENDPOINT = "https://openapi-rdc.aliyuncs.com";
    private static final Gson JSON = new Gson();
    private static final int PAGE_SIZE = 100;

    @FunctionalInterface
    interface Transport {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final String scope;
    private final String token;
    private final Transport transport;

    public YunxiaoClient(String instance, String organizationId, String token) {
        this(instance, organizationId, token, httpTransport());
    }

    YunxiaoClient(String instance, String organizationId, String token, Transport transport) {
        String org = organizationId.strip();
        String endpoint = instance.isBlank() ? CENTRAL_ENDPOINT : instance.strip();
        URI address;
        try {
            address = URI.create(endpoint);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("请输入有效的 HTTPS 云效实例地址。");
        }
        if (!"https".equalsIgnoreCase(address.getScheme()) || address.getHost() == null
                || address.getUserInfo() != null || address.getQuery() != null || address.getFragment() != null
                || !Set.of("", "/").contains(address.getPath())) {
            throw new IllegalArgumentException("云效接入地址只填写 HTTPS 实例域名。");
        }
        boolean central = address.getHost().equalsIgnoreCase(URI.create(CENTRAL_ENDPOINT).getHost());
        if (central && (org.isEmpty() || address.getPort() != -1 && address.getPort() != 443)) {
            throw new IllegalArgumentException("中心版需要填写组织 ID。");
        }
        if (!central && !org.isEmpty()) {
            throw new IllegalArgumentException("Region 版只填写实例地址，组织 ID 留空。");
        }
        this.token = token.strip();
        if (this.token.isEmpty() || !this.token.matches("[!-~]+")) {
            throw new IllegalArgumentException("请输入有效的云效个人访问令牌。");
        }
        String host = address.getHost().toLowerCase(Locale.ROOT);
        int port = address.getPort();
        scope = "https://" + host + (port == -1 || port == 443 ? "" : ":" + port)
                + "/oapi/v1/projex" + (central ? "/organizations/" + id(org) : "");
        this.transport = transport;
    }

    public String scope() {
        return scope;
    }

    public String workItemPage(String workItemId) {
        return scope.startsWith(CENTRAL_ENDPOINT + "/")
                ? "https://devops.aliyun.com/projex/task/" + id(workItemId) : "";
    }

    public Page<Choice> projects(int page) throws IOException, InterruptedException {
        return search("/projects:search", Map.of("page", page, "perPage", PAGE_SIZE), page,
                project -> new Choice(requiredId(project, "id"), string(project, "name")));
    }

    public Page<Item> requirements(String projectId, int page) throws IOException, InterruptedException {
        return workItems(projectId, "Req", page);
    }

    public Page<Item> projectTasks(String projectId, int page) throws IOException, InterruptedException {
        Page<Item> result = workItems(projectId, "Task", page);
        List<Item> tasks = result.items().stream()
                .filter(item -> projectId.equals(item.projectId()) && item.parentId().isBlank()
                        && "Task".equals(item.category()))
                .toList();
        return new Page<>(tasks, result.next());
    }

    private Page<Item> workItems(String projectId, String category, int page) throws IOException, InterruptedException {
        return search("/workitems:search", Map.of("spaceId", id(projectId), "spaceType", "Project",
                "category", category, "page", page, "perPage", PAGE_SIZE), page, YunxiaoClient::item);
    }

    public List<Item> subtasks(String requirementId) throws IOException, InterruptedException {
        List<JsonObject> relations = objects(request("GET", "/workitems/" + id(requirementId)
                + "/relationRecords?relationType=SUB", null).body());
        List<Item> children = new ArrayList<>();
        Set<String> seenChildIds = new HashSet<>();
        for (JsonObject relation : relations) {
            if (!"WORKITEM".equals(string(relation, "resourceType"))) {
                continue;
            }
            String childId = requiredId(relation, "resourceId");
            if (seenChildIds.add(childId)) {
                Item child = workItem(childId);
                if ("Task".equals(child.category()) && requirementId.equals(child.parentId())) {
                    children.add(child);
                }
            }
        }
        return List.copyOf(children);
    }

    public Item workItem(String id) throws IOException, InterruptedException {
        return item(workItemDetails(id));
    }

    private JsonObject workItemDetails(String workItemId) throws IOException, InterruptedException {
        return object(parse(request("GET", "/workitems/" + id(workItemId), null).body()));
    }

    public List<Choice> taskTypes(String projectId, String parentType) throws IOException, InterruptedException {
        List<JsonObject> relations = objects(request("GET", "/workitemTypes/" + id(parentType)
                + "/relationWorkitemTypes?relationType=SUB", null).body());
        Set<String> allowedTypeIds = new HashSet<>();
        for (JsonObject relation : relations) {
            if ("SUB".equals(string(relation, "relationType"))) {
                objects(relation.get("workitemTypeList")).forEach(type -> allowedTypeIds.add(requiredId(type, "id")));
            }
        }
        return taskTypes(projectId).stream().filter(type -> allowedTypeIds.contains(type.id())).toList();
    }

    public List<Choice> taskTypes(String projectId) throws IOException, InterruptedException {
        return objects(request("GET", "/projects/" + id(projectId) + "/workitemTypes?category=Task", null).body())
                .stream().filter(type -> bool(type, "enable", true))
                .sorted(Comparator.comparing(type -> !bool(type, "defaultType", false)))
                .map(type -> new Choice(requiredId(type, "id"), string(type, "name"))).toList();
    }

    public List<Choice> members(String projectId) throws IOException, InterruptedException {
        return objects(request("GET", "/projects/" + id(projectId) + "/members", null).body()).stream()
                .map(member -> new Choice(requiredId(member, "userId"), string(member, "userName")))
                .distinct().toList();
    }

    public List<Field> creationFields(String projectId, String typeId) throws IOException, InterruptedException {
        return objects(request("GET", "/projects/" + id(projectId) + "/workitemTypes/" + id(typeId)
                + "/fields", null).body()).stream()
                .filter(field -> bool(field, "showWhenCreate", true))
                .map(field -> new Field(string(field, "id"), string(field, "name"), string(field, "type"),
                        string(field, "format"), string(field, "defaultValue"), bool(field, "required", false),
                        fieldOptions(field))).toList();
    }

    public String createTask(String projectId, String parentId, String typeId, String subject,
                             String assignee, String description, Map<String, String> custom)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("spaceId", id(projectId),
                "workitemTypeId", id(typeId), "subject", subject, "assignedTo", id(assignee),
                "description", description, "formatType", "MARKDOWN", "customFieldValues", custom));
        if (parentId != null) {
            body.put("parentId", id(parentId));
        }
        return createdId(request("POST", "/workitems", body));
    }

    public List<Effort> efforts(String workItemId) throws IOException, InterruptedException {
        return effortList("/workitems/" + id(workItemId) + "/effortRecords");
    }

    public List<Effort> estimatedEfforts(String workItemId) throws IOException, InterruptedException {
        return effortList("/workitems/" + id(workItemId) + "/estimatedEfforts");
    }

    private List<Effort> effortList(String path) throws IOException, InterruptedException {
        return objects(request("GET", path, null).body()).stream()
                .map(record -> new Effort(requiredId(record, "id"), string(record, "description"),
                        string(record, "actualTime"), string(record, "gmtStart"), string(record, "gmtEnd"))).toList();
    }

    public Planning planning(String workItemId) throws IOException, InterruptedException {
        JsonObject item = workItemDetails(workItemId);
        return new Planning(fieldValue(item, PLAN_START_FIELD), fieldValue(item, PLAN_END_FIELD),
                nestedId(item, "assignedTo"));
    }

    public void updatePlanDates(String workItemId, String start, String end) throws IOException, InterruptedException {
        Map<String, String> fields = new LinkedHashMap<>();
        if (!start.isBlank()) {
            fields.put(PLAN_START_FIELD, start);
        }
        if (!end.isBlank()) {
            fields.put(PLAN_END_FIELD, end);
        }
        request("PUT", "/workitems/" + id(workItemId), fields);
    }

    private static String fieldValue(JsonObject item, String fieldId) {
        if (item.get("customFieldValues") instanceof JsonArray fields) {
            for (JsonElement element : fields) {
                if (element instanceof JsonObject field && fieldId.equals(string(field, "fieldId"))
                        && field.get("values") instanceof JsonArray values && !values.isEmpty()
                        && values.get(0) instanceof JsonObject value) {
                    return string(value, "identifier");
                }
            }
        }
        return "";
    }

    public String saveEstimatedEffort(String workItemId, String recordId, String owner, BigDecimal hours,
                                      String description) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of("owner", id(owner), "spentTime", hours, "description", description);
        return saveEffortRecord("/workitems/" + id(workItemId) + "/estimatedEfforts", recordId, body);
    }

    public String saveEffort(String workItemId, String recordId, LocalDate day, BigDecimal hours,
                             String description) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("actualTime", hours);
        body.addProperty("gmtStart", day.toString());
        body.addProperty("gmtEnd", day.toString());
        body.addProperty("containsRestDay", true);
        body.addProperty("description", description);
        return saveEffortRecord("/workitems/" + id(workItemId) + "/effortRecords", recordId, body);
    }

    private String saveEffortRecord(String path, String recordId, Object body)
            throws IOException, InterruptedException {
        if (recordId.isBlank()) {
            return createdId(request("POST", path, body));
        }
        request("PUT", path + "/" + id(recordId), body);
        return recordId;
    }

    private <T> Page<T> search(String path, Map<String, ?> query, int page, Function<JsonObject, T> read)
            throws IOException, InterruptedException {
        HttpResponse<String> response = request("POST", path, query);
        List<T> items = objects(response.body()).stream().map(read).toList();
        Optional<String> nextHeader = response.headers().firstValue("x-next-page");
        int next = page + 1;
        if (nextHeader.isPresent()) {
            String value = nextHeader.get().strip();
            next = value.matches("[0-9]{1,5}") ? Integer.parseInt(value) : 0;
        } else if (items.size() < PAGE_SIZE) {
            next = 0;
        }
        return new Page<>(items, next > page && next <= 100 ? next : 0);
    }

    private HttpResponse<String> request(String method, String path, Object body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(scope + path)).timeout(Duration.ofSeconds(18))
                .header("x-yunxiao-token", token).header("Accept", "application/json");
        HttpRequest.BodyPublisher payload = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            builder.header("Content-Type", "application/json");
            payload = HttpRequest.BodyPublishers.ofString(JSON.toJson(body));
        }
        HttpResponse<String> response = transport.send(builder.method(method, payload).build());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new ApiException(status, errorMessage(response));
        }
        return response;
    }

    private String errorMessage(HttpResponse<String> response) {
        String prefix = "云效请求失败（HTTP " + response.statusCode() + "）";
        try {
            JsonObject error = object(parse(response.body()));
            for (String field : List.of("message", "errorMessage", "errorMsg")) {
                String detail = string(error, field).replace(token, "****").strip();
                if (!detail.isBlank()) {
                    return prefix + "。 " + detail.substring(0, Math.min(240, detail.length()));
                }
            }
        } catch (ProtocolException ignored) {
            // An HTML or empty error response still has a useful HTTP status.
        }
        return prefix + "。请检查连接、令牌权限和项目配置。";
    }

    private static Transport httpTransport() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return request -> http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String createdId(HttpResponse<String> response) {
        return requiredId(object(parse(response.body())), "id");
    }

    private static Item item(JsonObject value) {
        return new Item(requiredId(value, "id"), string(value, "serialNumber"), string(value, "subject"),
                nestedId(value, "workitemType"), nestedId(value, "space"), string(value, "categoryId"),
                string(value, "parentId"), string(value, "description"));
    }

    private static List<Choice> fieldOptions(JsonObject field) {
        if (!(field.get("options") instanceof JsonArray options)) {
            return List.of();
        }
        boolean priority = "priority".equals(string(field, "id"));
        return objects(options).stream().map(option -> {
            String value = string(option, "value");
            String label = string(option, "displayValue");
            // Projex priority values can be display names. The create API requires the option ID.
            String optionId = priority ? string(option, "id") : value;
            if (priority && optionId.isBlank() && value.matches("[A-Za-z0-9_-]+")) {
                optionId = value;
            }
            return new Choice(optionId, label.isBlank() ? value : label);
        }).filter(option -> !option.id().isBlank()).toList();
    }

    private static String nestedId(JsonObject value, String key) {
        return value.get(key) instanceof JsonObject nested ? string(nested, "id") : "";
    }

    private static String requiredId(JsonObject value, String key) {
        String result = string(value, key);
        if (!result.matches("[A-Za-z0-9_-]+")) {
            throw new ProtocolException("云效返回的数据缺少有效 ID。");
        }
        return result;
    }

    private static String id(String value) {
        if (!value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("云效 ID 为空或格式无效。");
        }
        return value;
    }

    private static String string(JsonObject value, String key) {
        JsonElement field = value.get(key);
        return field != null && field.isJsonPrimitive() ? field.getAsString() : "";
    }

    private static boolean bool(JsonObject value, String key, boolean fallback) {
        String field = string(value, key);
        return field.isBlank() ? fallback : Boolean.parseBoolean(field);
    }

    private static List<JsonObject> objects(String body) {
        return objects(parse(body));
    }

    private static List<JsonObject> objects(JsonElement value) {
        if (!(value instanceof JsonArray array)) {
            throw new ProtocolException("云效返回的数据格式与预期不符。");
        }
        List<JsonObject> items = new ArrayList<>();
        array.forEach(element -> items.add(object(element)));
        return items;
    }

    private static JsonObject object(JsonElement value) {
        if (!(value instanceof JsonObject object)) {
            throw new ProtocolException("云效返回的数据格式与预期不符。");
        }
        return object;
    }

    private static JsonElement parse(String body) {
        try {
            return JsonParser.parseString(body);
        } catch (JsonParseException invalid) {
            throw new ProtocolException("云效返回的数据格式与预期不符。", invalid);
        }
    }
}
