package com.sg.shougongji.repository;

import com.google.gson.Gson;
import com.sg.shougongji.model.Yunxiao.Assignee;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.model.Yunxiao.ChildDraft;
import com.sg.shougongji.model.Yunxiao.ConnectionSettings;
import com.sg.shougongji.model.Yunxiao.PendingEffort;
import com.sg.shougongji.model.Yunxiao.PendingWrite;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class YunxiaoRepository {
    private static final Gson JSON = new Gson();
    private final WorkLogDatabase database;

    public YunxiaoRepository(WorkLogDatabase database) {
        this.database = database;
    }

    public ConnectionSettings findConnection() throws SQLException {
        return database.read(YunxiaoMapper.class, YunxiaoMapper::findConnection);
    }

    public void saveConnection(ConnectionSettings settings) throws SQLException {
        database.restrictFileAccess();
        database.write(YunxiaoMapper.class, mapper -> {
            mapper.enableSecureDelete();
            mapper.saveConnection(settings);
        });
    }

    public void deleteConnection() throws SQLException {
        database.write(YunxiaoMapper.class, mapper -> {
            mapper.enableSecureDelete();
            mapper.deleteConnection();
        });
    }

    public Assignee findAssignee(String scope) throws SQLException {
        return database.read(YunxiaoMapper.class, mapper -> mapper.findAssignee(scope));
    }

    public void saveAssignee(String scope, Assignee assignee) throws SQLException {
        database.write(YunxiaoMapper.class, mapper -> mapper.saveAssignee(scope, assignee));
    }

    public Map<Long, Binding> findBindings() throws SQLException {
        List<YunxiaoMapper.TaskBinding> rows = database.read(YunxiaoMapper.class, YunxiaoMapper::findBindings);
        Map<Long, Binding> bindings = new HashMap<>();
        for (YunxiaoMapper.TaskBinding row : rows) {
            bindings.put(row.taskId(), row.binding());
        }
        return bindings;
    }

    public Binding findBinding(long taskId) throws SQLException {
        return database.read(YunxiaoMapper.class, mapper -> mapper.findBinding(taskId));
    }

    public void bind(long taskId, Binding binding) throws SQLException {
        database.write(YunxiaoMapper.class, mapper -> {
            if (mapper.saveBinding(taskId, binding) != 1) {
                throw new IllegalArgumentException("该事项已关联其他云效子任务，请使用不同的本地事项名称。");
            }
        });
    }

    public long taskForExistingItem(Binding binding, String title) throws SQLException {
        return database.writeReturning(YunxiaoMapper.class, mapper -> {
            Long existing = mapper.findTaskForItem(binding);
            if (existing != null) {
                return existing;
            }
            long taskId = insertTimerTask(mapper, binding.scope(), null, title);
            mapper.saveBinding(taskId, binding);
            return taskId;
        });
    }

    public long prepareAutomaticChild(String scope, ChildDraft draft) throws SQLException {
        return database.writeReturning(YunxiaoMapper.class, mapper -> {
            long taskId = childTimerTask(mapper, scope, draft.parent().id(), draft.title());
            mapper.saveChildDraft(taskId, JSON.toJson(draft));
            return taskId;
        });
    }

    private static long childTimerTask(YunxiaoMapper mapper, String scope, String parentId, String title) {
        Long existing = mapper.findChildTimerTask(scope, parentId, title);
        return existing != null ? existing : insertTimerTask(mapper, scope, parentId, title);
    }

    private static long insertTimerTask(YunxiaoMapper mapper, String scope, String parentId, String title) {
        // 云效事项按工作项归属区分，展示标题可以与其他事项相同。
        long taskId = mapper.insertTimerTask("yunxiao:" + UUID.randomUUID());
        mapper.saveTimerTitle(taskId, scope, parentId, title);
        return taskId;
    }

    public ChildDraft findChildDraft(long taskId, String scope) throws SQLException {
        return database.read(YunxiaoMapper.class,
                mapper -> JSON.fromJson(mapper.findChildDraft(taskId, scope), ChildDraft.class));
    }

    public List<PendingEffort> pendingEfforts(String scope) throws SQLException {
        return database.read(YunxiaoMapper.class, mapper -> mapper.findPendingEfforts(scope));
    }

    public PendingEffort pendingEffort(long taskId, String day) throws SQLException {
        return database.read(YunxiaoMapper.class, mapper -> mapper.findPendingEffort(taskId, day));
    }

    public int pendingEffortCount() throws SQLException {
        return database.read(YunxiaoMapper.class, YunxiaoMapper::pendingEffortCount);
    }

    public Set<Long> pendingEffortTasks(String day) throws SQLException {
        return database.read(YunxiaoMapper.class, mapper -> Set.copyOf(mapper.pendingEffortTasks(day)));
    }

    public void completePendingEffort(PendingEffort effort) throws SQLException {
        database.write(YunxiaoMapper.class, mapper -> mapper.completePendingEffort(effort));
    }

    public boolean hasCreatedTask(long taskId, Binding binding) throws SQLException {
        return database.read(YunxiaoMapper.class, mapper -> mapper.hasCreatedTask(taskId, binding));
    }

    public PendingWrite reserveWrite(long taskId, String scope, String operation) throws SQLException {
        String marker = "收工记#" + UUID.randomUUID();
        return database.writeReturning(YunxiaoMapper.class, mapper -> {
            PendingWrite write = mapper.reserveWrite(taskId, scope, operation, marker);
            if (write == null) {
                throw new SQLException("无法保存云效提交状态");
            }
            return write;
        });
    }

    public void completeWrite(String marker, String remoteId) throws SQLException {
        database.write(YunxiaoMapper.class, mapper -> {
            if (mapper.completeWrite(marker, remoteId) != 1) {
                throw new SQLException("无法保存云效返回的记录 ID");
            }
        });
    }

    public void releaseWrite(String marker) throws SQLException {
        database.write(YunxiaoMapper.class, mapper -> mapper.releaseWrite(marker));
    }
}
