package com.sg.shougongji.repository;

import com.sg.shougongji.model.WorkSession;
import com.sg.shougongji.model.WorkTask;

import java.sql.SQLException;
import java.util.List;

public final class TimerRepository {
    private final WorkLogDatabase database;

    public TimerRepository(WorkLogDatabase database) {
        this.database = database;
    }

    public List<WorkTask> findTasks(boolean recentOnly) throws SQLException {
        return database.read(TimerMapper.class, mapper -> mapper.findTasks(recentOnly));
    }

    public WorkSession findActiveSession() throws SQLException {
        return database.read(TimerMapper.class, TimerMapper::findActiveSession);
    }

    public List<WorkSession> findCompletedSessions(long rangeStart, long rangeEnd) throws SQLException {
        return database.read(TimerMapper.class, mapper -> mapper.findCompletedSessions(rangeStart, rangeEnd));
    }

    /** 通过同一会话执行整次计时变更，回调结束后提交或回滚。 */
    public void inTransaction(TimerChange change) throws SQLException {
        database.write(TimerMapper.class, change::apply);
    }

    @FunctionalInterface
    public interface TimerChange {
        void apply(TimerMapper records) throws SQLException;
    }
}
