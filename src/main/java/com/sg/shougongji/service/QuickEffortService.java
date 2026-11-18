package com.sg.shougongji.service;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.sg.shougongji.model.DayReview;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.model.Yunxiao.Choice;
import com.sg.shougongji.model.Yunxiao.Item;
import com.sg.shougongji.repository.YunxiaoRepository;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QuickEffortService {
    private static final Gson JSON = new Gson();
    private static final Type ROWS = new TypeToken<List<Row>>() {}.getType();

    public record Form(List<Row> rows, List<Binding> yesterday, List<Binding> recent) {}

    public record Row(Binding binding, String hours, String note, boolean submitted) {}

    private final YunxiaoRepository repository;
    private final ReviewService review;

    public QuickEffortService(YunxiaoRepository repository, ReviewService review) {
        this.repository = repository;
        this.review = review;
    }

    public Form load(YunxiaoService connection, LocalDate day) throws SQLException {
        String scope = connection.scope();
        List<Row> saved = readDraft(scope, day);
        Map<Long, Binding> bindings = repository.findBindings();
        List<Row> rows = saved == null ? timedRows(scope, day, bindings) : saved;

        LocalDate yesterday = day.minusDays(1);
        Map<String, Binding> previous = new LinkedHashMap<>();
        List<Row> previousDraft = readDraft(scope, yesterday);
        if (previousDraft != null) {
            previousDraft.forEach(row -> remember(previous, row.binding()));
        }
        timedRows(scope, yesterday, bindings).forEach(row -> remember(previous, row.binding()));
        repository.findRegisteredBindings(scope, yesterday.toString()).forEach(binding -> remember(previous, binding));

        Map<String, Binding> recent = new LinkedHashMap<>();
        repository.findQuickEffortBindings(scope).forEach(binding -> remember(recent, binding));
        return new Form(rows, List.copyOf(previous.values()), List.copyOf(recent.values()));
    }

    public Binding addTask(YunxiaoService connection, Choice project, Item task) throws SQLException {
        return repository.findBinding(connection.timerTask(project, task));
    }

    public synchronized void saveDraft(YunxiaoService connection, LocalDate day, List<Row> rows) throws SQLException {
        String scope = connection.scope();
        Set<String> itemIds = new HashSet<>();
        for (Row row : rows) {
            checkScope(scope, row.binding());
            if (!itemIds.add(row.binding().childId())) {
                throw new IllegalArgumentException("同一工作项每天只登记一行，请合并工时后重试。");
            }
        }
        repository.saveQuickEffortDraft(scope, day.toString(), JSON.toJson(rows, ROWS));
    }

    public void submit(YunxiaoService connection, LocalDate day, Row row, BigDecimal hours,
                       boolean retryConfirmed, boolean verify) throws SQLException, IOException, InterruptedException {
        checkScope(connection.scope(), row.binding());
        if (hours.signum() <= 0 || hours.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("请输入大于 0 的小时数，最多两位小数。");
        }
        connection.registerQuickEffort(row.binding(), day, hours, row.note(), review, retryConfirmed, verify);
        markSubmitted(connection.scope(), day, row);
    }

    private List<Row> timedRows(String scope, LocalDate day, Map<Long, Binding> bindings) throws SQLException {
        Map<String, Binding> targets = new LinkedHashMap<>();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (DayReview.Entry entry : review.loadDay(day).entries()) {
            Binding binding = bindings.get(entry.task().id());
            if (binding != null && scope.equals(binding.scope())) {
                remember(targets, binding);
                totals.merge(binding.childId(), entry.seconds(), Long::sum);
            }
        }
        List<Row> rows = new ArrayList<>();
        targets.forEach((itemId, binding) -> {
            BigDecimal hours = YunxiaoService.effortHours(totals.get(itemId));
            rows.add(new Row(binding, hours.signum() > 0 ? hours.stripTrailingZeros().toPlainString() : "", "", false));
        });
        return List.copyOf(rows);
    }

    private List<Row> readDraft(String scope, LocalDate day) throws SQLException {
        String saved = repository.findQuickEffortDraft(scope, day.toString());
        if (saved == null) {
            return null;
        }
        try {
            List<Row> rows = JSON.fromJson(saved, ROWS);
            return List.copyOf(rows);
        } catch (JsonParseException | NullPointerException invalid) {
            throw new SQLException("不能读取快速登记草稿。", invalid);
        }
    }

    private synchronized void markSubmitted(String scope, LocalDate day, Row sent) throws SQLException {
        List<Row> saved = readDraft(scope, day);
        Row submitted = new Row(sent.binding(), sent.hours(), sent.note(), true);
        if (saved == null) {
            repository.saveQuickEffortDraft(scope, day.toString(), JSON.toJson(List.of(submitted), ROWS));
            return;
        }
        int index = saved.indexOf(sent);
        if (index >= 0) {
            List<Row> updated = new ArrayList<>(saved);
            updated.set(index, submitted);
            repository.saveQuickEffortDraft(scope, day.toString(), JSON.toJson(updated, ROWS));
        }
    }

    private static void checkScope(String scope, Binding binding) {
        if (!scope.equals(binding.scope())) {
            throw new IllegalArgumentException("请连接该任务所在的云效组织或实例。");
        }
    }

    private static void remember(Map<String, Binding> bindings, Binding binding) {
        bindings.putIfAbsent(binding.childId(), binding);
    }
}
