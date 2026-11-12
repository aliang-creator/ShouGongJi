package com.sg.shougongji.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record YunxiaoWeek(String accountName, List<Day> days, List<Registration> multiDay,
                          int workItemCount, List<String> unavailableItems, int unreadableRecords) {
    public BigDecimal dailyHours() {
        return days.stream().map(Day::hours).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public record Day(LocalDate date, List<Registration> records) {
        public BigDecimal hours() {
            return records.stream().map(Registration::hours).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public record Registration(String workItem, BigDecimal hours, LocalDate start, LocalDate end,
                               String category, String description) {}
}
