package com.sg.shougongji.model;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

public final class Yunxiao {
    public static final String PLAN_START_FIELD = "79";
    public static final String PLAN_END_FIELD = "80";

    private Yunxiao() {}

    public record ConnectionSettings(String instance, String organizationId, String accessToken) {
        @Override
        public String toString() {
            return "ConnectionSettings[accessToken=****]";
        }
    }

    public record Assignee(String memberId, String name) {}

    public record Binding(String scope, String childId, String label) {}

    public record PendingWrite(String marker, String remoteId, boolean fresh) {}

    public record ChildDraft(Choice project, Item parent, String typeId, String title, String assignee,
                             String description, Map<String, String> fields) {}

    public record PendingEffort(long taskId, String day, String revision, String title) {}

    public record Choice(String id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    public record Item(String id, String serial, String subject, String typeId, String projectId,
                       String category, String parentId, String description) {
        @Override
        public String toString() {
            return serial.isBlank() ? subject : serial + "  " + subject;
        }
    }

    public enum WorkItemKind {
        REQUIREMENT("Req"),
        INDEPENDENT_TASK("Task"),
        SUBTASK("Task");

        private final String category;

        WorkItemKind(String category) {
            this.category = category;
        }

        public String category() {
            return category;
        }

        public boolean matches(Item item) {
            return category.equals(item.category()) && switch (this) {
                case REQUIREMENT -> true;
                case INDEPENDENT_TASK -> item.parentId().isBlank();
                case SUBTASK -> !item.parentId().isBlank();
            };
        }
    }

    public record Page<T>(List<T> items, int next) {}

    public record Planning(String start, String end, String assigneeId) {}

    public record Field(String id, String name, String kind, String format, String defaultValue,
                        boolean required, List<Choice> options) {
        public String plannedDate(LocalDate day) {
            if (!"SystemCustomField".equals(kind) || !"date".equalsIgnoreCase(format)) {
                return "";
            }
            return switch (id) {
                case PLAN_START_FIELD -> day + " 00:00:00";
                case PLAN_END_FIELD -> day + " 23:59:59";
                default -> "";
            };
        }
    }

    public record Effort(String id, String description, String hours, String start, String end) {
        public boolean matches(LocalDate day, BigDecimal amount) {
            try {
                return day.equals(effortDate(start)) && day.equals(effortDate(end))
                        && amount.compareTo(new BigDecimal(hours)) == 0;
            } catch (DateTimeException | NumberFormatException invalid) {
                return false;
            }
        }
    }

    public static final class ApiException extends IOException {
        private final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public boolean rejected() {
            return switch (status) {
                case 400, 401, 403, 404, 422, 429 -> true;
                default -> false;
            };
        }

        public boolean restDayRejected() {
            return status == 400 && getMessage().contains("包含休息日");
        }
    }

    public static final class ProtocolException extends RuntimeException {
        public ProtocolException(String message) {
            super(message);
        }

        public ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static LocalDate effortDate(String value) {
        if (value.matches("-?[0-9]+")) {
            // Projex can return date-only workdays as epoch milliseconds at UTC+08:00 midnight.
            return Instant.ofEpochMilli(Long.parseLong(value)).atOffset(ZoneOffset.ofHours(8)).toLocalDate();
        }
        return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
    }
}
