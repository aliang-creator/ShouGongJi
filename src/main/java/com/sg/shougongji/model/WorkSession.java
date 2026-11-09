package com.sg.shougongji.model;

public record WorkSession(long id, WorkTask task, long start, Long end, String note, boolean manual, boolean estimated) {}
