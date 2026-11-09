package com.sg.shougongji.service;

import com.sg.shougongji.AppServices;
import com.sg.shougongji.model.DayReview;
import com.sg.shougongji.model.WorkSession;
import com.sg.shougongji.model.WorkTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class TimerServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);

    @TempDir
    Path directory;

    private final TestClock clock = new TestClock();
    private AppServices services;

    @BeforeEach
    void open() throws Exception {
        services = reopen();
    }

    @Test
    void interruptionAndReturnKeepTwoOriginalSegmentsUnderOneTask() throws Exception {
        at("09:00");
        services.timer().start("需求开发");
        at("09:50");
        services.timer().start("协助排查");
        at("10:15");
        services.timer().start("需求开发");
        at("11:00");
        pause();

        DayReview review = services.review().loadDay(DAY);
        assertEquals(120 * 60, review.seconds());
        assertEquals(3, review.segments().size());
        assertEquals(2, review.entries().size());
        DayReview.Entry feature = review.entries().getFirst();
        assertEquals(95 * 60, feature.seconds());
        assertEquals(2, feature.segments().size());
        assertEquals(25 * 60, review.entries().getLast().seconds());
        assertNull(services.timer().active());
    }

    @Test
    void manualEntriesCanTouchBoundariesButCannotOverlapActiveOrCompletedSessions() throws Exception {
        at("11:00");
        services.timer().start("当前事项");
        at("12:00");
        assertThrows(IllegalArgumentException.class, () -> services.timer().saveSession(null, "重叠会议",
                local("10:50"), local("11:15"), "", false));
        services.timer().saveSession(null, "会议", local("10:30"), local("11:00"), "确认上线范围", true);
        assertThrows(IllegalArgumentException.class, () -> services.timer().saveSession(null, "重复补录",
                local("10:40"), local("10:50"), "", false));
        DayReview review = services.review().loadDay(DAY);
        assertEquals(1800, review.seconds());
        assertEquals(1800, review.estimatedSeconds());

        WorkSession meeting = review.segments().getFirst().session();
        services.timer().saveSession(meeting, meeting.task().title(), local("10:35"), local("11:00"), "确认上线范围", false);
        services.timer().updateWorkItem(meeting.task().id(), "PROJ-123");
        review = services.review().loadDay(DAY);
        assertEquals(1500, review.seconds());
        assertEquals(0, review.estimatedSeconds());
        assertTrue(review.segments().getFirst().session().manual());
        assertTrue(review.clipboardText().contains("PROJ-123"));
        assertTrue(review.clipboardText().contains("确认上线范围"));
        assertEquals("当前事项", services.timer().active().task().title());
    }

    @Test
    void midnightClipsEachDaysTotalWithoutSplittingTheStoredSession() throws Exception {
        at("23:40");
        services.timer().start("值班");
        clock.time = DAY.plusDays(1).atTime(0, 20).atZone(ZONE).toInstant();
        pause();

        DayReview before = services.review().loadDay(DAY);
        DayReview after = services.review().loadDay(DAY.plusDays(1));
        assertEquals(1200, before.seconds());
        assertEquals(1200, after.seconds());
        assertEquals(before.segments().getFirst().session(), after.segments().getFirst().session());
        assertEquals(before.segments().getFirst().end(), after.segments().getFirst().start());
    }

    @Test
    void reopenCanConfirmEarlierEndWithoutChargingTheIdleTime() throws Exception {
        at("09:00");
        services.timer().start("整理需求");
        at("14:00");

        AppServices reopened = reopen();
        assertEquals("整理需求", reopened.timer().active().task().title());
        assertEquals(0, reopened.review().loadDay(DAY).seconds());
        reopened.timer().pause(reopened.timer().active().id(), local("09:35"), true);
        DayReview review = reopened.review().loadDay(DAY);
        assertEquals(2100, review.seconds());
        assertEquals(2100, review.estimatedSeconds());
        assertNull(reopened.timer().active());
    }

    @Test
    void failedSwitchKeepsThePreviousActiveSession() throws Exception {
        at("09:00");
        services.timer().start("原事项");
        WorkSession active = services.timer().active();
        at("08:59");
        assertThrows(IllegalArgumentException.class, () -> services.timer().start("新事项"));
        assertEquals(active, services.timer().active());
        assertEquals(1, services.timer().tasks().size());
    }

    @Test
    void removingFromRecentKeepsHistoryAndPersistsUntilTheTaskIsTimedAgain() throws Exception {
        at("09:00");
        services.timer().start("会议");
        at("09:30");
        pause();
        WorkTask task = services.timer().recentTasks().getFirst();

        services.timer().hideFromRecent(task.id());
        services.timer().hideFromRecent(task.id());
        services = reopen();
        assertTrue(services.timer().recentTasks().isEmpty());
        assertEquals(task, services.timer().tasks().getFirst());
        assertEquals(1800, services.review().loadDay(DAY).seconds());

        at("10:00");
        services.timer().start(task.title());
        at("10:15");
        pause();
        assertEquals(task, services.timer().recentTasks().getFirst());
        assertEquals(2700, services.review().loadDay(DAY).seconds());
    }

    @Test
    void deletingATaskRemovesItsHistoryAcrossDaysAndProtectsActiveTiming() throws Exception {
        at("09:00");
        services.timer().start("保留事项");
        at("09:30");
        services.timer().start("待删事项");
        WorkSession running = services.timer().active();
        WorkTask task = running.task();
        LocalDate yesterday = DAY.minusDays(1);
        Instant yesterdayStart = yesterday.atTime(10, 0).atZone(ZONE).toInstant();
        services.timer().saveSession(null, task.title(), yesterdayStart, yesterdayStart.plusSeconds(600), "备注", true);
        services.timer().updateWorkItem(task.id(), "PROJ-123");

        assertThrows(IllegalArgumentException.class, () -> services.timer().deleteTask(task.id()));
        assertEquals(running.id(), services.timer().active().id());
        assertEquals(600, services.review().loadDay(yesterday).seconds());

        at("10:00");
        services.timer().start("保留事项");
        services.timer().hideFromRecent(task.id());
        services.timer().deleteTask(task.id());
        assertEquals("保留事项", services.timer().active().task().title());
        assertEquals(1, services.timer().tasks().size());
        assertEquals(1800, services.review().loadDay(DAY).seconds());
        assertTrue(services.review().loadDay(yesterday).segments().isEmpty());

        at("10:15");
        services.timer().start(task.title());
        assertTrue(services.timer().active().task().workItem().isEmpty());
        assertEquals(task.title(), services.timer().recentTasks().getFirst().title());
    }

    private AppServices reopen() throws Exception {
        return AppServices.open(directory.resolve("worklog.sqlite"), clock);
    }

    private void pause() throws Exception {
        services.timer().pause(services.timer().active().id(), clock.instant(), false);
    }

    private void at(String value) {
        clock.time = local(value);
    }

    private static Instant local(String value) {
        return java.time.LocalTime.parse(value).atDate(DAY).atZone(ZONE).toInstant();
    }

    private static final class TestClock extends Clock {
        private Instant time = Instant.EPOCH;

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(time, zone);
        }

        @Override
        public Instant instant() {
            return time;
        }
    }
}
