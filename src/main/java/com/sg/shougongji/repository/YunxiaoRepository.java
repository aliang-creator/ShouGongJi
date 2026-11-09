package com.sg.shougongji.repository;

import com.sg.shougongji.model.Yunxiao.Assignee;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.model.Yunxiao.ConnectionSettings;
import com.sg.shougongji.model.Yunxiao.PendingWrite;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class YunxiaoRepository {
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

    public void bind(long taskId, Binding binding) throws SQLException {
        database.write(YunxiaoMapper.class, mapper -> {
            if (mapper.saveBinding(taskId, binding) != 1) {
                throw new IllegalArgumentException("该事项已关联其他云效子任务，请使用不同的本地事项名称。");
            }
        });
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
