package com.sg.shougongji.service;

import com.sg.shougongji.model.DayReview;
import com.sg.shougongji.model.WorkSession;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.repository.TimerRepository;
import com.sg.shougongji.repository.YunxiaoRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReviewService {
    private final TimerRepository timer;
    private final YunxiaoRepository yunxiao;
    private final ZoneId zone;

    public ReviewService(TimerRepository timer, YunxiaoRepository yunxiao, ZoneId zone) {
        this.timer = timer;
        this.yunxiao = yunxiao;
        this.zone = zone;
    }

    public DayReview loadDay(LocalDate date) throws SQLException {
        long dayStart = date.atStartOfDay(zone).toEpochSecond();
        long nextDayStart = date.plusDays(1).atStartOfDay(zone).toEpochSecond();
        List<WorkSession> sessions = timer.findCompletedSessions(dayStart, nextDayStart);
        return DayReview.summarize(date, dayStart, nextDayStart, sessions);
    }

    public Overview loadOverview(LocalDate date) throws SQLException {
        return new Overview(loadDay(date), yunxiao.findBindings(), timer.findActiveSession(),
                yunxiao.pendingEffortTasks(date.toString()));
    }

    long linkedSeconds(LocalDate date, long taskId, Binding target) throws SQLException {
        Map<Long, Binding> bindings = target == null ? Map.of() : yunxiao.findBindings();
        return loadDay(date).entries().stream()
                .filter(entry -> entry.task().id() == taskId || sameWorkItem(target, bindings.get(entry.task().id())))
                .mapToLong(DayReview.Entry::seconds).sum();
    }

    private static boolean sameWorkItem(Binding target, Binding candidate) {
        return target != null && candidate != null && target.scope().equals(candidate.scope())
                && target.childId().equals(candidate.childId());
    }

    public record Overview(DayReview review, Map<Long, Binding> bindings, WorkSession activeSession,
                           Set<Long> pendingEffortTasks) {}
}
