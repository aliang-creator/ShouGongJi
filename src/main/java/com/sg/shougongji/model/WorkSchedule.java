package com.sg.shougongji.model;

public record WorkSchedule(int morningStart, int morningEnd, int afternoonStart, int afternoonEnd) {
    public static final WorkSchedule DEFAULT = new WorkSchedule(9 * 60 + 20, 12 * 60 + 20,
            13 * 60 + 50, 18 * 60 + 50);

    public WorkSchedule {
        if (morningStart < 0 || morningStart >= morningEnd || morningEnd > afternoonStart
                || afternoonStart >= afternoonEnd || afternoonEnd >= 24 * 60) {
            throw new IllegalArgumentException("每段的结束时间须晚于开始时间，上午和下午时段不能重叠。");
        }
    }
}
