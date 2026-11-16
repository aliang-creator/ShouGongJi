package com.sg.shougongji.model;

import com.sg.shougongji.model.Yunxiao.Binding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record YunxiaoWeek(String accountName, List<Day> days, List<Registration> multiDay,
                          List<Binding> workItems, List<String> unavailableItems, int unreadableRecords) {
    public int workItemCount() {
        return workItems.size();
    }

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
