package com.sg.shougongji.service;

import com.sg.shougongji.model.WorkSession;
import com.sg.shougongji.model.WorkTask;
import com.sg.shougongji.repository.TimerMapper;
import com.sg.shougongji.repository.TimerRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

public final class TimerService {
    private static final long NO_SESSION_ID = -1;

    private final TimerRepository repository;
    private final Clock clock;

    public TimerService(TimerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public long now() {
        return clock.instant().getEpochSecond();
    }

    public List<WorkTask> tasks() throws SQLException {
        return repository.findTasks(false);
    }

    public List<WorkTask> recentTasks() throws SQLException {
        return repository.findTasks(true);
    }

    public void hideFromRecent(long taskId) throws SQLException {
        repository.inTransaction(mapper -> mapper.hideFromRecent(taskId));
    }

    public void deleteTask(long taskId) throws SQLException {
        repository.inTransaction(mapper -> {
            WorkSession activeSession = mapper.findActiveSession();
            if (activeSession != null && activeSession.task().id() == taskId) {
                throw new IllegalArgumentException("请先暂停这个事项的计时，再删除记录。");
            }
            // 计时段通过外键引用事项，先删计时段，再删事项。
            mapper.deleteSessionsForTask(taskId);
            mapper.deleteTask(taskId);
        });
    }

    public WorkSession active() throws SQLException {
        return repository.findActiveSession();
    }

    public void start(String title) throws SQLException {
        repository.inTransaction(mapper -> {
            long startedAt = now();
            WorkSession activeSession = mapper.findActiveSession();
            long taskId = findOrCreateTaskId(mapper, title);
            if (activeSession != null && activeSession.task().id() == taskId) {
                return;
            }
            if (activeSession != null) {
                // 切换时用同一时刻结束旧计时、开始新计时，两个写入一起提交。
                finishSession(mapper, activeSession, startedAt, false);
            }
            ensureNoOverlap(mapper, NO_SESSION_ID, startedAt, Long.MAX_VALUE);
            mapper.insertActiveSession(taskId, startedAt);
            mapper.showInRecent(taskId);
        });
    }

    public void pause(long sessionId, Instant end, boolean estimated) throws SQLException {
        repository.inTransaction(mapper -> {
            WorkSession activeSession = mapper.findActiveSession();
            if (activeSession == null || activeSession.id() != sessionId) {
                throw new IllegalArgumentException("正在计时的事项已变更，请重新打开计时窗口。");
            }
            long endedAt = end.getEpochSecond();
            if (endedAt > now()) {
                throw new IllegalArgumentException("结束时间不能晚于现在。");
            }
            finishSession(mapper, activeSession, endedAt, estimated);
        });
    }

    public void saveSession(WorkSession original, String title, Instant start, Instant end, String note, boolean estimated)
            throws SQLException {
        long startedAt = start.getEpochSecond();
        long endedAt = end.getEpochSecond();
        if (startedAt >= endedAt || endedAt > now()) {
            throw new IllegalArgumentException("结束时间须晚于开始时间，且不能晚于现在。");
        }
        boolean isNewSession = original == null;
        repository.inTransaction(mapper -> {
            // 编辑时排除原计时段，避免拿新时间和这条记录自身比较。
            long sessionIdToIgnore = isNewSession ? NO_SESSION_ID : original.id();
            ensureNoOverlap(mapper, sessionIdToIgnore, startedAt, endedAt);
            long taskId = findOrCreateTaskId(mapper, title);
            String normalizedNote = note.strip();
            int affectedRows;
            if (isNewSession) {
                affectedRows = mapper.insertManualSession(taskId, startedAt, endedAt, normalizedNote, estimated);
            } else {
                affectedRows = mapper.updateCompletedSession(original.id(), taskId, startedAt, endedAt,
                        normalizedNote, estimated);
            }
            if (affectedRows != 1) {
                throw new IllegalArgumentException("这段记录已变更，请刷新后重试。");
            }
            if (isNewSession) {
                mapper.showInRecent(taskId);
            }
        });
    }

    public void updateWorkItem(long taskId, String value) throws SQLException {
        repository.inTransaction(mapper -> mapper.updateWorkItem(taskId, value.strip()));
    }

    private static void finishSession(TimerMapper mapper, WorkSession session, long endedAt, boolean estimated) {
        if (endedAt < session.start()) {
            throw new IllegalArgumentException("结束时间不能早于开始时间。");
        }
        if (endedAt == session.start()) {
            // 同一秒内开始又结束时，丢弃这个零时长计时段。
            mapper.deleteSession(session.id());
        } else {
            mapper.finishSession(session.id(), endedAt, estimated);
        }
    }

    private static void ensureNoOverlap(TimerMapper mapper, long sessionIdToIgnore, long startedAt, long endedAt) {
        String overlappingTitle = mapper.findOverlappingTaskTitle(sessionIdToIgnore, startedAt, endedAt);
        if (overlappingTitle != null) {
            throw new IllegalArgumentException("这段时间与「" + overlappingTitle + "」重叠，请调整起止时间。");
        }
    }

    private static long findOrCreateTaskId(TimerMapper mapper, String title) throws SQLException {
        String normalizedTitle = title.strip();
        if (normalizedTitle.isEmpty()) {
            throw new IllegalArgumentException("请输入事项名称。");
        }
        mapper.insertTaskIfAbsent(normalizedTitle);
        Long taskId = mapper.findTaskId(normalizedTitle);
        if (taskId == null) {
            throw new SQLException("无法读取事项");
        }
        return taskId;
    }
}
