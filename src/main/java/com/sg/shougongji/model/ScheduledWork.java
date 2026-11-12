package com.sg.shougongji.model;

public record ScheduledWork(long position, long taskId, long seconds, String note, boolean estimated) {}
