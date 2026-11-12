package com.sg.shougongji.repository;

import com.sg.shougongji.model.ScheduledWork;
import com.sg.shougongji.model.WorkSchedule;
import com.sg.shougongji.model.WorkSession;
import com.sg.shougongji.model.WorkTask;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TimerMapper {
    List<WorkTask> findTasks(@Param("recentOnly") boolean recentOnly);

    void insertTaskIfAbsent(@Param("title") String title);

    Long findTaskId(@Param("title") String title);

    boolean taskExists(@Param("taskId") long taskId);

    boolean automaticallyRegistersEffort(@Param("taskId") long taskId);

    void enqueueEffort(@Param("taskId") long taskId, @Param("day") String day);

    void updateWorkItem(@Param("taskId") long taskId, @Param("workItem") String workItem);

    void hideFromRecent(@Param("taskId") long taskId);

    void showInRecent(@Param("taskId") long taskId);

    void deleteTask(@Param("taskId") long taskId);

    void deleteSessionsForTask(@Param("taskId") long taskId);

    WorkSession findActiveSession();

    List<WorkSession> findCompletedSessions(@Param("rangeStart") long rangeStart, @Param("rangeEnd") long rangeEnd);

    WorkSchedule findWorkSchedule();

    void saveWorkSchedule(WorkSchedule schedule);

    List<Long> findScheduledSessionIds(@Param("day") String day);

    List<ScheduledWork> findScheduledWork(@Param("day") String day);

    void deleteScheduledSessions(@Param("day") String day);

    void markScheduledSession(@Param("day") String day, @Param("position") long position);

    void unmarkScheduledSession(@Param("sessionId") long sessionId);

    String findOverlappingTaskTitle(@Param("sessionIdToIgnore") long sessionIdToIgnore,
                                    @Param("startedAt") long startedAt, @Param("endedAt") long endedAt,
                                    @Param("includeScheduled") boolean includeScheduled);

    void insertActiveSession(@Param("taskId") long taskId, @Param("startedAt") long startedAt);

    int insertCompletedSession(@Param("taskId") long taskId, @Param("startedAt") long startedAt,
                               @Param("endedAt") long endedAt, @Param("note") String note,
                               @Param("estimated") boolean estimated, @Param("manual") boolean manual);

    int updateCompletedSession(@Param("sessionId") long sessionId, @Param("taskId") long taskId,
                               @Param("startedAt") long startedAt, @Param("endedAt") long endedAt,
                               @Param("note") String note, @Param("estimated") boolean estimated);

    void finishSession(@Param("sessionId") long sessionId, @Param("endedAt") long endedAt,
                       @Param("estimated") boolean estimated);

    void deleteSession(@Param("sessionId") long sessionId);
}
