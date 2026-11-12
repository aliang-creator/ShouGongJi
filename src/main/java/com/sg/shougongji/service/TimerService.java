package com.sg.shougongji.service;

import com.sg.shougongji.model.DayReview;
import com.sg.shougongji.model.ScheduledWork;
import com.sg.shougongji.model.WorkSchedule;
import com.sg.shougongji.model.WorkSession;
import com.sg.shougongji.model.WorkTask;
import com.sg.shougongji.repository.TimerMapper;
import com.sg.shougongji.repository.TimerRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

    public WorkSchedule workSchedule() throws SQLException {
        return Objects.requireNonNullElse(repository.findWorkSchedule(), WorkSchedule.DEFAULT);
    }

    public void saveWorkSchedule(WorkSchedule schedule) throws SQLException {
        repository.inTransaction(mapper -> mapper.saveWorkSchedule(schedule));
    }

    public void start(String title) throws SQLException {
        repository.inTransaction(mapper -> start(mapper, findOrCreateTaskId(mapper, title)));
    }

    public void start(long taskId) throws SQLException {
        repository.inTransaction(mapper -> start(mapper, requireTask(mapper, taskId)));
    }

    private void start(TimerMapper mapper, long taskId) {
        long startedAt = now();
        WorkSession activeSession = mapper.findActiveSession();
        if (activeSession != null && activeSession.task().id() == taskId) {
            return;
        }
        if (activeSession != null) {
            // 切换时用同一时刻结束旧计时、开始新计时，两个写入一起提交。
            finishSession(mapper, activeSession, startedAt, false);
        }
        // 自动排出的时间用于工时归档，实时计时按实际时刻记录。
        ensureNoOverlap(mapper, NO_SESSION_ID, startedAt, Long.MAX_VALUE, false);
        mapper.insertActiveSession(taskId, startedAt);
        mapper.showInRecent(taskId);
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
        Long existingTask = original != null && original.task().title().equals(title.strip())
                ? original.task().id() : null;
        saveSession(original, existingTask, title, start, end, note, estimated);
    }

    public void saveSession(WorkSession original, Long existingTask, String title,
                            Instant start, Instant end, String note, boolean estimated) throws SQLException {
        long startedAt = start.getEpochSecond();
        long endedAt = end.getEpochSecond();
        if (startedAt >= endedAt || endedAt > now()) {
            throw new IllegalArgumentException("结束时间须晚于开始时间，且不能晚于现在。");
        }
        boolean isNewSession = original == null;
        repository.inTransaction(mapper -> {
            // 编辑时排除原计时段，避免拿新时间和这条记录自身比较。
            long sessionIdToIgnore = isNewSession ? NO_SESSION_ID : original.id();
            ensureNoOverlap(mapper, sessionIdToIgnore, startedAt, endedAt, true);
            long taskId = existingTask == null ? findOrCreateTaskId(mapper, title) : requireTask(mapper, existingTask);
            String normalizedNote = note.strip();
            int affectedRows;
            if (isNewSession) {
                affectedRows = mapper.insertCompletedSession(taskId, startedAt, endedAt, normalizedNote, estimated, true);
            } else {
                affectedRows = mapper.updateCompletedSession(original.id(), taskId, startedAt, endedAt,
                        normalizedNote, estimated);
                mapper.unmarkScheduledSession(original.id());
            }
            if (affectedRows != 1) {
                throw new IllegalArgumentException("这段记录已变更，请刷新后重试。");
            }
            if (isNewSession) {
                mapper.showInRecent(taskId);
            }
        });
    }

    public void addWorkHours(LocalDate day, Long existingTask, String title, long seconds, String note, boolean estimated)
            throws SQLException {
        saveWorkHours(day, null, existingTask, title, seconds, note, estimated);
    }

    public void editWorkHours(LocalDate day, DayReview.Entry entry, long seconds, String note, boolean estimated)
            throws SQLException {
        saveWorkHours(day, entry, entry.task().id(), entry.task().title(), seconds, note, estimated);
    }

    private void saveWorkHours(LocalDate day, DayReview.Entry original, Long existingTask, String title,
                               long seconds, String note, boolean estimated) throws SQLException {
        if (seconds <= 0 || seconds > 25 * 3600) {
            throw new IllegalArgumentException("请输入有效工时，例如 3 或 1.5 小时。");
        }
        long dayStart = day.atStartOfDay(clock.getZone()).toEpochSecond();
        long dayEnd = day.plusDays(1).atStartOfDay(clock.getZone()).toEpochSecond();
        String dayKey = day.toString();
        repository.inTransaction(mapper -> {
            long taskId = existingTask == null ? findOrCreateTaskId(mapper, title) : requireTask(mapper, existingTask);
            List<WorkSession> completed = mapper.findCompletedSessions(dayStart, dayEnd);
            if (original != null) {
                List<WorkSession> current = completed.stream().filter(session -> session.task().id() == taskId).toList();
                if (!current.equals(original.segments().stream().map(DayReview.Segment::session).toList())) {
                    throw new IllegalArgumentException("该事项当天的记录已变更，请刷新后重试。");
                }
            }

            Set<Long> scheduledIds = new HashSet<>(mapper.findScheduledSessionIds(dayKey));
            List<WorkSession> fixed = completed.stream().filter(session -> !scheduledIds.contains(session.id())).toList();
            List<TimeSlot> occupied = new ArrayList<>(fixed.stream()
                    .filter(session -> original == null || session.task().id() != taskId)
                    .map(session -> new TimeSlot(session.start(), session.end())).toList());
            WorkSession active = mapper.findActiveSession();
            long currentTime = now();
            if (active != null && active.start() < currentTime) {
                occupied.add(new TimeSlot(active.start(), currentTime));
                occupied.sort(Comparator.comparingLong(TimeSlot::start));
            }

            List<ScheduledWork> work = new ArrayList<>(mapper.findScheduledWork(dayKey));
            long position = work.isEmpty() ? 1 : work.getLast().position() + 1;
            if (original != null) {
                position = work.stream().filter(item -> item.taskId() == taskId).findFirst()
                        .map(ScheduledWork::position).orElse(position);
                work.removeIf(item -> item.taskId() == taskId);
            }
            work.add(new ScheduledWork(position, taskId, seconds, note.strip(), estimated));
            work.sort(Comparator.comparingLong(ScheduledWork::position));

            WorkSchedule schedule = Objects.requireNonNullElse(mapper.findWorkSchedule(), WorkSchedule.DEFAULT);
            Deque<TimeSlot> free = new ArrayDeque<>();
            addFreeSlots(free, occupied, atMinute(day, schedule.morningStart()),
                    atMinute(day, schedule.morningEnd()));
            addFreeSlots(free, occupied, atMinute(day, schedule.afternoonStart()),
                    atMinute(day, schedule.afternoonEnd()));
            long available = free.stream().mapToLong(slot -> slot.end() - slot.start()).sum();
            long required = work.stream().mapToLong(ScheduledWork::seconds).sum();
            if (required > available) {
                long remaining = Math.max(0, available - required + seconds);
                throw new IllegalArgumentException("当天剩余上班时段不足，此次最多可填 "
                        + DayReview.duration(remaining) + "。请减少工时或调整上班时段。");
            }

            mapper.deleteScheduledSessions(dayKey);
            if (original != null) {
                for (WorkSession session : fixed) {
                    if (session.task().id() == taskId) {
                        keepOutsideDay(mapper, session, dayStart, dayEnd);
                    }
                }
            }
            placeWork(mapper, dayKey, work, free);
            if (original == null) {
                mapper.showInRecent(taskId);
            }
        });
    }

    private long atMinute(LocalDate day, int minute) {
        var local = day.atStartOfDay().plusMinutes(minute);
        var zone = clock.getZone();
        var transition = zone.getRules().getTransition(local);
        return transition != null && transition.isGap()
                ? transition.getInstant().getEpochSecond() : local.atZone(zone).toEpochSecond();
    }

    private static void addFreeSlots(Deque<TimeSlot> free, List<TimeSlot> occupied, long start, long end) {
        long cursor = start;
        for (TimeSlot slot : occupied) {
            if (cursor >= end || slot.start() >= end) {
                break;
            }
            if (slot.start() > cursor) {
                free.addLast(new TimeSlot(cursor, slot.start()));
            }
            cursor = Math.max(cursor, slot.end());
        }
        if (cursor < end) {
            free.addLast(new TimeSlot(cursor, end));
        }
    }

    private static void keepOutsideDay(TimerMapper mapper, WorkSession session, long dayStart, long dayEnd) {
        mapper.deleteSession(session.id());
        long taskId = session.task().id();
        if (session.start() < dayStart) {
            mapper.insertCompletedSession(taskId, session.start(), dayStart, session.note(), session.estimated(), session.manual());
        }
        if (session.end() > dayEnd) {
            mapper.insertCompletedSession(taskId, dayEnd, session.end(), session.note(), session.estimated(), session.manual());
        }
    }

    private static void placeWork(TimerMapper mapper, String day, List<ScheduledWork> work, Deque<TimeSlot> free) {
        for (ScheduledWork item : work) {
            long remaining = item.seconds();
            while (remaining > 0) {
                TimeSlot slot = free.removeFirst();
                long used = Math.min(remaining, slot.end() - slot.start());
                long end = slot.start() + used;
                mapper.insertCompletedSession(item.taskId(), slot.start(), end, item.note(), item.estimated(), true);
                mapper.markScheduledSession(day, item.position());
                remaining -= used;
                if (end < slot.end()) {
                    free.addFirst(new TimeSlot(end, slot.end()));
                }
            }
        }
    }

    private record TimeSlot(long start, long end) {}

    public void updateWorkItem(long taskId, String value) throws SQLException {
        repository.inTransaction(mapper -> mapper.updateWorkItem(taskId, value.strip()));
    }

    private void finishSession(TimerMapper mapper, WorkSession session, long endedAt, boolean estimated) {
        if (endedAt < session.start()) {
            throw new IllegalArgumentException("结束时间不能早于开始时间。");
        }
        if (endedAt == session.start()) {
            // 同一秒内开始又结束时，丢弃这个零时长计时段。
            mapper.deleteSession(session.id());
        } else {
            mapper.finishSession(session.id(), endedAt, estimated);
            enqueueEffort(mapper, session.task().id(), session.start(), endedAt);
        }
    }

    private void enqueueEffort(TimerMapper mapper, long taskId, long startedAt, long endedAt) {
        if (!mapper.automaticallyRegistersEffort(taskId)) {
            return;
        }
        LocalDate firstDay = Instant.ofEpochSecond(startedAt).atZone(clock.getZone()).toLocalDate();
        LocalDate lastDay = Instant.ofEpochSecond(endedAt - 1).atZone(clock.getZone()).toLocalDate();
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            mapper.enqueueEffort(taskId, day.toString());
        }
    }

    private static void ensureNoOverlap(TimerMapper mapper, long sessionIdToIgnore, long startedAt, long endedAt,
                                        boolean includeScheduled) {
        String overlappingTitle = mapper.findOverlappingTaskTitle(sessionIdToIgnore, startedAt, endedAt, includeScheduled);
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

    private static long requireTask(TimerMapper mapper, long taskId) {
        if (!mapper.taskExists(taskId)) {
            throw new IllegalArgumentException("该事项已删除，请重新选择。");
        }
        return taskId;
    }
}
