package com.sg.shougongji.service;

import com.sg.shougongji.model.Yunxiao.PendingEffort;
import com.sg.shougongji.repository.YunxiaoRepository;

import java.io.IOException;
import java.sql.SQLException;

public final class YunxiaoAutoSync {
    private final YunxiaoRepository repository;
    private final ReviewService review;

    public YunxiaoAutoSync(YunxiaoRepository repository, ReviewService review) {
        this.repository = repository;
        this.review = review;
    }

    public int pendingCount() throws SQLException {
        return repository.pendingEffortCount();
    }

    public boolean isAutomaticTask(long taskId) throws SQLException {
        return repository.findChildDraft(taskId, null) != null;
    }

    public Failure sync(YunxiaoService connection, PendingEffort confirmedRetry)
            throws SQLException, InterruptedException {
        Failure firstFailure = null;
        for (PendingEffort pending : repository.pendingEfforts(connection.scope())) {
            try {
                connection.syncTimedEffort(pending, review, pending.equals(confirmedRetry));
            } catch (IOException | SQLException | IllegalArgumentException failure) {
                if (firstFailure == null) {
                    firstFailure = new Failure(pending, failure);
                }
            }
        }
        return firstFailure;
    }

    public record Failure(PendingEffort effort, Exception cause) {}
}
