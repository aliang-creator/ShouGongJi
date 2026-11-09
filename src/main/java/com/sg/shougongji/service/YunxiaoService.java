package com.sg.shougongji.service;

import com.sg.shougongji.model.Yunxiao.ApiException;
import com.sg.shougongji.model.Yunxiao.Assignee;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.model.Yunxiao.Choice;
import com.sg.shougongji.model.Yunxiao.Effort;
import com.sg.shougongji.model.Yunxiao.Field;
import com.sg.shougongji.model.Yunxiao.Item;
import com.sg.shougongji.model.Yunxiao.Page;
import com.sg.shougongji.model.Yunxiao.PendingWrite;
import com.sg.shougongji.model.Yunxiao.Planning;
import com.sg.shougongji.model.Yunxiao.ProtocolException;
import com.sg.shougongji.repository.YunxiaoRepository;
import com.sg.shougongji.repository.yunxiao.YunxiaoClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class YunxiaoService {
    public static final class UncertainWrite extends IOException {
        UncertainWrite() {
            super("上一次写入没有确认结果。请先在云效核对，确认没有重复记录后再重试。");
        }
    }

    public static final class PartialSubmission extends IOException {
        PartialSubmission(Exception cause) {
            super("实际工时已提交，计划日期或预计工时同步未完成。", cause);
        }
    }

    public static final class ManualEffortRequired extends IOException {
        private final String newRecordDescription;

        ManualEffortRequired(PendingWrite write, ApiException cause) {
            super("云效开放接口拒绝了休息日工时，请在网页勾选「包含休息日」。", cause);
            newRecordDescription = write.remoteId().isBlank() ? effortDescription(write.marker()) : "";
        }

        public String newRecordDescription() {
            return newRecordDescription;
        }
    }

    private final YunxiaoRepository repository;
    private final YunxiaoClient client;

    public YunxiaoService(YunxiaoRepository repository, YunxiaoClient client) {
        this.repository = repository;
        this.client = client;
    }

    public String scope() {
        return client.scope();
    }

    public String workItemPage(String workItemId) {
        return client.workItemPage(workItemId);
    }

    public Assignee defaultAssignee() throws SQLException {
        return repository.findAssignee(scope());
    }

    public void saveDefaultAssignee(Assignee assignee) throws SQLException {
        if (assignee.memberId().isBlank()) {
            throw new IllegalArgumentException("请选择一位默认负责人。");
        }
        repository.saveAssignee(scope(), assignee);
    }

    public Page<Choice> projects(int page) throws IOException, InterruptedException {
        return client.projects(page);
    }

    public List<Choice> members(String projectId) throws IOException, InterruptedException {
        return client.members(projectId);
    }

    public Page<Item> requirements(String projectId, int page) throws IOException, InterruptedException {
        return client.requirements(projectId, page);
    }

    public Page<Item> projectTasks(String projectId, int page) throws IOException, InterruptedException {
        return client.projectTasks(projectId, page);
    }

    public List<Item> subtasks(String requirementId) throws IOException, InterruptedException {
        return client.subtasks(requirementId);
    }

    public List<Choice> taskTypes(String projectId) throws IOException, InterruptedException {
        return client.taskTypes(projectId);
    }

    public List<Choice> taskTypes(String projectId, String parentTypeId) throws IOException, InterruptedException {
        return client.taskTypes(projectId, parentTypeId);
    }

    public List<Field> creationFields(String projectId, String typeId) throws IOException, InterruptedException {
        return client.creationFields(projectId, typeId);
    }

    public Binding bind(long taskId, Choice project, Item parent,
                                      Item item) throws SQLException {
        String expectedParent = parent == null ? "" : parent.id();
        if (!project.id().equals(item.projectId()) || !expectedParent.equals(item.parentId())
                || !"Task".equals(item.category())) {
            throw new IllegalArgumentException("所选任务与当前项目或需求不一致。");
        }
        return saveLocalBinding(taskId, item.id(), label(project, parent, item.toString()));
    }

    public Binding create(long taskId, Choice project, Item parent,
                                        String typeId, String title, String assignee, Map<String, String> fields,
                                        boolean retryConfirmed)
            throws SQLException, IOException, InterruptedException {
        return create(taskId, project, parent, typeId, title, assignee, "", fields, retryConfirmed);
    }

    public Binding create(long taskId, Choice project, Item parent,
                                        String typeId, String title, String assignee, String description,
                                        Map<String, String> fields, boolean retryConfirmed)
            throws SQLException, IOException, InterruptedException {
        if (parent != null && (!project.id().equals(parent.projectId()) || !"Req".equals(parent.category()))) {
            throw new IllegalArgumentException("所选需求与当前项目不一致。");
        }
        String parentId = parent == null ? null : parent.id();
        String operation = parentId == null ? "project-task:" + project.id() : "child:" + parentId;
        PendingWrite pendingWrite = repository.reserveWrite(taskId, client.scope(), operation);
        String itemId = pendingWrite.remoteId();
        if (itemId.isBlank() && !pendingWrite.fresh()) {
            // 上一次请求可能已成功，但本地未收到结果。先用标记在云效查找，避免重复创建。
            String marker = pendingWrite.marker();
            List<String> matches;
            if (parentId == null) {
                matches = matchingProjectTasks(project.id(), marker);
            } else {
                matches = client.subtasks(parentId).stream()
                        .filter(item -> project.id().equals(item.projectId()) && item.description().contains(marker))
                        .map(Item::id)
                        .toList();
            }
            itemId = resolveExistingRecordId(matches, retryConfirmed);
        }
        if (itemId.isBlank()) {
            String body = description.isBlank() ? "由收工记创建" : description.stripTrailing();
            String markedDescription = body + "\n\n" + pendingWrite.marker();
            itemId = createRemoteRecord(pendingWrite, () -> client.createTask(project.id(), parentId, typeId, title,
                    assignee, markedDescription, fields));
        } else if (pendingWrite.remoteId().isBlank()) {
            repository.completeWrite(pendingWrite.marker(), itemId);
        }
        return saveLocalBinding(taskId, itemId, label(project, parent, title));
    }

    private List<String> matchingProjectTasks(String projectId, String marker) throws IOException, InterruptedException {
        List<String> matches = new ArrayList<>();
        int page = 1;
        while (page > 0) {
            Page<Item> result = client.projectTasks(projectId, page);
            result.items().stream().filter(item -> item.description().contains(marker))
                    .map(Item::id).forEach(matches::add);
            page = result.next();
        }
        return matches.stream().distinct().toList();
    }

    public void submit(long taskId, Binding binding, LocalDate day, BigDecimal hours,
                       boolean retryConfirmed)
            throws SQLException, IOException, InterruptedException {
        checkScope(binding);
        String workItemId = binding.childId();
        String operation = "effort:" + workItemId + ":" + day;
        PendingWrite pendingWrite = repository.reserveWrite(taskId, client.scope(), operation);
        try {
            saveEffort(pendingWrite, retryConfirmed, () -> client.efforts(workItemId),
                    (recordId, marker) -> client.saveEffort(workItemId, recordId, day, hours, effortDescription(marker)));
        } catch (ApiException failure) {
            if (!failure.restDayRejected()) {
                throw failure;
            }
            PendingWrite currentWrite = repository.reserveWrite(taskId, client.scope(), operation);
            if (!confirmActualEffort(workItemId, currentWrite, day, hours)) {
                throw new ManualEffortRequired(currentWrite, failure);
            }
        }
        syncPlanAndEstimate(taskId, binding, day, hours, retryConfirmed);
    }

    public void confirmWebEffort(long taskId, Binding binding, LocalDate day, BigDecimal hours,
                                 boolean retryConfirmed)
            throws SQLException, IOException, InterruptedException {
        checkScope(binding);
        String workItemId = binding.childId();
        String operation = "effort:" + workItemId + ":" + day;
        PendingWrite pendingWrite = repository.reserveWrite(taskId, client.scope(), operation);
        if (!confirmActualEffort(workItemId, pendingWrite, day, hours)) {
            throw new IllegalArgumentException("还未核对到这笔登记，请检查云效中的日期、小时数和登记说明。");
        }
        syncPlanAndEstimate(taskId, binding, day, hours, retryConfirmed);
    }

    private void checkScope(Binding binding) {
        if (!client.scope().equals(binding.scope())) {
            throw new IllegalArgumentException("请连接该任务所在的云效组织或实例。");
        }
    }

    private boolean confirmActualEffort(String workItemId, PendingWrite pendingWrite, LocalDate day,
                                        BigDecimal hours)
            throws IOException, InterruptedException, SQLException {
        String savedRecordId = pendingWrite.remoteId();
        List<Effort> matches = client.efforts(workItemId).stream()
                .filter(record -> savedRecordId.isBlank() ? record.description().contains(pendingWrite.marker())
                        : savedRecordId.equals(record.id()))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalArgumentException("云效有多条匹配登记，请先核对重复工时。");
        }
        if (matches.isEmpty() || !matches.getFirst().matches(day, hours)) {
            return false;
        }
        if (savedRecordId.isBlank()) {
            repository.completeWrite(pendingWrite.marker(), matches.getFirst().id());
        }
        return true;
    }

    private void syncPlanAndEstimate(long taskId, Binding binding, LocalDate day, BigDecimal hours,
                                     boolean retryConfirmed) throws IOException {
        try {
            // 只为收工记创建的任务同步计划日期和预计工时。
            if (!repository.hasCreatedTask(taskId, binding)) {
                return;
            }
            String workItemId = binding.childId();
            String owner = fillMissingPlanDates(workItemId, day);
            String operation = "estimate:" + workItemId + ":" + day;
            PendingWrite pendingWrite = repository.reserveWrite(taskId, client.scope(), operation);
            saveEffort(pendingWrite, retryConfirmed, () -> client.estimatedEfforts(workItemId),
                    (recordId, marker) -> client.saveEstimatedEffort(workItemId, recordId, owner, hours,
                            day + " 由收工记登记\n" + marker));
        } catch (IOException | SQLException | InterruptedException | IllegalArgumentException | ProtocolException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PartialSubmission(failure);
        }
    }

    private String fillMissingPlanDates(String workItemId, LocalDate day) throws IOException, InterruptedException {
        Planning planning = client.planning(workItemId);
        // 已填的一端保留原值；只缺一端时沿用另一端的日期。
        String startToFill = planning.start().isBlank() ? datePart(planning.end(), day) + " 00:00:00" : "";
        String endToFill = planning.end().isBlank() ? datePart(planning.start(), day) + " 23:59:59" : "";
        if (!startToFill.isBlank() || !endToFill.isBlank()) {
            client.updatePlanDates(workItemId, startToFill, endToFill);
        }
        if (planning.assigneeId().isBlank()) {
            throw new IllegalArgumentException("该云效任务还没有负责人，无法登记预计工时。");
        }
        return planning.assigneeId();
    }

    private static String datePart(String existing, LocalDate fallback) {
        return existing.length() >= 10 && existing.substring(0, 10).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                ? existing.substring(0, 10) : fallback.toString();
    }

    private static String effortDescription(String marker) {
        return "由收工记登记\n" + marker;
    }

    private void saveEffort(PendingWrite pendingWrite, boolean retryConfirmed, EffortLookup lookup,
                            EffortSave save)
            throws SQLException, IOException, InterruptedException {
        String marker = pendingWrite.marker();
        String recordId = pendingWrite.remoteId();
        if (recordId.isBlank() && !pendingWrite.fresh()) {
            List<String> matches = lookup.run().stream()
                    .filter(record -> record.description().contains(marker))
                    .map(Effort::id)
                    .toList();
            recordId = resolveExistingRecordId(matches, retryConfirmed);
        }
        if (recordId.isBlank()) {
            createRemoteRecord(pendingWrite, () -> save.run("", marker));
            return;
        }
        if (pendingWrite.remoteId().isBlank()) {
            repository.completeWrite(marker, recordId);
        }
        save.run(recordId, marker);
    }

    private Binding saveLocalBinding(long taskId, String workItemId, String title) throws SQLException {
        Binding binding = new Binding(client.scope(), workItemId, title);
        repository.bind(taskId, binding);
        return binding;
    }

    private static String label(Choice project, Item parent, String child) {
        return parent == null ? project.name() + " / " + child : project.name() + " / " + parent + " / " + child;
    }

    private String createRemoteRecord(PendingWrite pendingWrite, RemoteCreate action)
            throws SQLException, IOException, InterruptedException {
        String recordId;
        try {
            recordId = action.run();
        } catch (ApiException failure) {
            if (pendingWrite.fresh() && failure.rejected() && !failure.restDayRejected()) {
                repository.releaseWrite(pendingWrite.marker());
            }
            throw failure;
        }
        repository.completeWrite(pendingWrite.marker(), recordId);
        return recordId;
    }

    private static String resolveExistingRecordId(List<String> matchingIds, boolean retryConfirmed)
            throws UncertainWrite {
        if (matchingIds.size() > 1) {
            throw new IllegalArgumentException("云效返回了多条匹配记录，请先在云效处理重复记录。");
        }
        if (!matchingIds.isEmpty()) {
            return matchingIds.getFirst();
        }
        if (!retryConfirmed) {
            throw new UncertainWrite();
        }
        return "";
    }

    @FunctionalInterface
    private interface RemoteCreate {
        String run() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    private interface EffortLookup {
        List<Effort> run() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    private interface EffortSave {
        String run(String recordId, String marker) throws IOException, InterruptedException;
    }
}
