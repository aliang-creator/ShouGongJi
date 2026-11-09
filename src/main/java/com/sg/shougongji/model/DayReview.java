package com.sg.shougongji.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public record DayReview(LocalDate date, List<Segment> segments, List<Entry> entries) {
    public record Segment(WorkSession session, long start, long end) {
        public long seconds() {
            return end - start;
        }
    }

    public record Entry(WorkTask task, List<Segment> segments) {
        public long seconds() {
            return segments.stream().mapToLong(Segment::seconds).sum();
        }

        public long estimatedSeconds() {
            return segments.stream().filter(segment -> segment.session().estimated()).mapToLong(Segment::seconds).sum();
        }
    }

    public static DayReview summarize(LocalDate date, long dayStart, long nextDayStart,
                                      List<WorkSession> sessions) {
        List<Segment> segments = new ArrayList<>();
        Map<WorkTask, List<Segment>> segmentsByTask = new LinkedHashMap<>();

        for (WorkSession session : sessions) {
            // 跨日的计时段只把落在当天的部分计入汇总。
            long startWithinDay = Math.max(dayStart, session.start());
            long endWithinDay = Math.min(nextDayStart, session.end());
            Segment segment = new Segment(session, startWithinDay, endWithinDay);
            segments.add(segment);
            segmentsByTask.computeIfAbsent(session.task(), task -> new ArrayList<>()).add(segment);
        }

        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<WorkTask, List<Segment>> group : segmentsByTask.entrySet()) {
            entries.add(new Entry(group.getKey(), List.copyOf(group.getValue())));
        }
        return new DayReview(date, List.copyOf(segments), List.copyOf(entries));
    }

    public long seconds() {
        return entries.stream().mapToLong(Entry::seconds).sum();
    }

    public long estimatedSeconds() {
        return entries.stream().mapToLong(Entry::estimatedSeconds).sum();
    }

    public String clipboardText() {
        return clipboardText(WorkTask::workItem);
    }

    public String clipboardText(Function<WorkTask, String> workItemLabel) {
        StringBuilder text = new StringBuilder("收工清单 · ").append(date).append(" · ").append(duration(seconds()));
        for (Entry entry : entries) {
            WorkTask task = entry.task();
            text.append("\n\n").append(task.title()).append(" · ").append(duration(entry.seconds()));
            long estimatedSeconds = entry.estimatedSeconds();
            if (estimatedSeconds > 0) {
                text.append("（含估算 ").append(duration(estimatedSeconds)).append("）");
            }
            String workItem = workItemLabel.apply(task);
            text.append("\n工作项：").append(workItem.isBlank() ? "待归属" : workItem);
            entry.segments().stream().map(segment -> segment.session().note()).filter(note -> !note.isBlank())
                    .distinct().forEach(note -> text.append("\n  · ").append(note.replace("\n", "\n    ")));
        }
        return text.toString();
    }

    public static String duration(long seconds) {
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long remainingSeconds = seconds % 60;
        StringBuilder text = new StringBuilder();
        if (hours > 0) {
            text.append(hours).append(" 小时");
        }
        if (minutes != 0) {
            text.append(text.isEmpty() ? "" : " ").append(minutes).append(" 分钟");
        }
        if (remainingSeconds != 0 || text.isEmpty()) {
            text.append(text.isEmpty() ? "" : " ").append(remainingSeconds).append(" 秒");
        }
        return text.toString();
    }
}
