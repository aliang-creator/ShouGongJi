package com.sg.shougongji.ui;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EffortDurationTest {
    @Test
    void acceptsDecimalHoursAndCommonTimeUnits() {
        assertEquals(new BigDecimal("1.50"), EffortDuration.parseHours("1.5"));
        assertEquals(new BigDecimal("0.75"), EffortDuration.parseHours("45m"));
        assertEquals(new BigDecimal("4.00"), EffortDuration.parseHours(" 4H "));
        assertEquals(new BigDecimal("1.33"), EffortDuration.parseHours("1小时 20分钟"));
        assertEquals(new BigDecimal("1.33"), EffortDuration.parseHours("1h20m"));
    }

    @Test
    void rejectsInputThatCannotBeRegistered() {
        for (String value : new String[]{"", "h", "-1h", "45m abc", "0", ".001", "1h2h"}) {
            assertThrows(IllegalArgumentException.class, () -> EffortDuration.parseHours(value), value);
        }
        assertEquals(new BigDecimal("30.00"), EffortDuration.parseHours("30"));
    }

    @Test
    void displaysTheCloudHoursInHoursAndMinutes() {
        assertEquals("6 小时 45 分钟", EffortDuration.display(new BigDecimal("6.75")));
    }
}
