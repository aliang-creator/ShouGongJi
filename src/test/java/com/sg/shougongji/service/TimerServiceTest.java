package com.sg.shougongji.service;

import com.sg.shougongji.AppServices;
import com.sg.shougongji.model.DayReview;
import com.sg.shougongji.model.WorkSchedule;
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
import java.util.List;

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
    void schedulesBySaveOrderSkipsLunchAndReflowsLaterWorkWhenHoursChange() throws Exception {
        at("20:00");
        WorkSchedule custom = new WorkSchedule(560, 740, 830, 1150);
        services.timer().saveWorkSchedule(custom);
        services = reopen();
        assertEquals(custom, services.timer().workSchedule());

        services.timer().addWorkHours(DAY, null, "事项甲", 3 * 3600, "甲备注", false);
        services.timer().addWorkHours(DAY, null, "事项乙", 150 * 60, "乙备注", true);
        services.timer().addWorkHours(DAY, null, "事项丙", 90 * 60, "", false);
        DayReview first = services.review().loadDay(DAY);
        assertEquals(List.of("09:20–12:20", "13:50–16:20", "16:20–17:50"), ranges(first));

        services = reopen();
        services.timer().editWorkHours(DAY, first.entries().getFirst(), 2 * 3600, "甲备注", false);
        DayReview edited = services.review().loadDay(DAY);
        assertEquals(List.of("09:20–11:20", "11:20–12:20", "13:50–15:20", "15:20–16:50"), ranges(edited));
        assertEquals(List.of("事项甲", "事项乙", "事项丙"),
                edited.entries().stream().map(entry -> entry.task().title()).toList());
        assertEquals(150 * 60, edited.entries().get(1).estimatedSeconds());
        assertEquals(List.of("乙备注"), edited.entries().get(1).notes());
    }

    @Test
    void canEditToEightHoursBeforeTheConfiguredWorkdayEnds() throws Exception {
        at("14:00");
        services.timer().start("欧洲DW改造");
        at("14:42");
        pause();
        at("15:27");
        DayReview.Entry entry = services.review().loadDay(DAY).entries().getFirst();

        services.timer().editWorkHours(DAY, entry, 8 * 3600, "", false);
        DayReview filled = services.review().loadDay(DAY);
        assertEquals(8 * 3600, filled.seconds());
        assertEquals(List.of("09:20–12:20", "13:50–18:50"), ranges(filled));
        assertThrows(IllegalArgumentException.class,
                () -> services.timer().addWorkHours(DAY, null, "超出时段", 60, "", false));
        assertEquals(filled, services.review().loadDay(DAY));

        at("15:30");
        services.timer().start("实时计时");
        at("15:35");
        pause();
        DayReview withLiveRecord = services.review().loadDay(DAY);
        assertEquals(List.of("09:20–12:20", "13:50–18:50", "15:30–15:35"), ranges(withLiveRecord));
        assertFalse(withLiveRecord.segments().getLast().session().manual());
    }

    @Test
    void leavesRecordedAndActiveTimeInPlaceAndRollsBackWhenWorkDoesNotFit() throws Exception {
        at("10:00");
        services.timer().start("已有计时");
        at("10:30");
        pause();
        at("14:00");
        services.timer().start("正在计时");
        WorkSession active = services.timer().active();
        at("15:00");
        services.timer().addWorkHours(DAY, null, "补录", 2 * 3600, "", false);
        DayReview before = services.review().loadDay(DAY);
        assertEquals(List.of("09:20–10:00", "10:00–10:30", "10:30–11:50"), ranges(before));

        IllegalArgumentException overflow = assertThrows(IllegalArgumentException.class,
                () -> services.timer().addWorkHours(DAY, null, "放不下的事项", 271 * 60, "", false));
        assertTrue(overflow.getMessage().contains("4 小时 30 分钟"));
        assertEquals(before, services.review().loadDay(DAY));
        assertEquals(active, services.timer().active());
        assertEquals(3, services.timer().tasks().size());
        services.timer().addWorkHours(DAY, null, "正好填满", 270 * 60, "", false);
        assertEquals(List.of("09:20–10:00", "10:00–10:30", "10:30–11:50", "11:50–12:20", "13:50–14:00", "15:00–18:50"),
                ranges(services.review().loadDay(DAY)));
    }

    @Test
    void editsOnlyTheSelectedDaysPartOfAnOlderCrossMidnightRecord() throws Exception {
        at("23:40");
        services.timer().start("值班");
        LocalDate tomorrow = DAY.plusDays(1);
        clock.time = tomorrow.atTime(0, 20).atZone(ZONE).toInstant();
        pause();
        DayReview before = services.review().loadDay(DAY);
        services.timer().editWorkHours(DAY, before.entries().getFirst(), 3 * 3600, "补齐", false);
        DayReview edited = services.review().loadDay(DAY);
        assertEquals(List.of("09:20–12:20"), ranges(edited));
        WorkSession nextDay = services.review().loadDay(tomorrow).segments().getFirst().session();
        assertEquals(tomorrow.atStartOfDay(ZONE).toEpochSecond(), nextDay.start());
        assertEquals(1200, nextDay.end() - nextDay.start());
        assertFalse(nextDay.manual());
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

    private static List<String> ranges(DayReview review) {
        return review.segments().stream().map(segment -> Instant.ofEpochSecond(segment.start()).atZone(ZONE).toLocalTime()
                + "–" + Instant.ofEpochSecond(segment.end()).atZone(ZONE).toLocalTime()).toList();
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
