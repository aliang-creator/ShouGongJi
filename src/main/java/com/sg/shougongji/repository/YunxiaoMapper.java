package com.sg.shougongji.repository;

import com.sg.shougongji.model.Yunxiao.Assignee;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.model.Yunxiao.ConnectionSettings;
import com.sg.shougongji.model.Yunxiao.PendingEffort;
import com.sg.shougongji.model.Yunxiao.PendingWrite;
import org.apache.ibatis.annotations.Param;

import java.util.List;

interface YunxiaoMapper {
    record TaskBinding(long taskId, Binding binding) {}

    ConnectionSettings findConnection();

    void enableSecureDelete();

    void saveConnection(ConnectionSettings settings);

    void deleteConnection();

    Assignee findAssignee(@Param("scope") String scope);

    void saveAssignee(@Param("scope") String scope, @Param("assignee") Assignee assignee);

    List<TaskBinding> findBindings();

    Binding findBinding(@Param("taskId") long taskId);

    Long findTaskForItem(@Param("binding") Binding binding);

    Long findChildTimerTask(@Param("scope") String scope, @Param("parentId") String parentId,
                            @Param("title") String title);

    long insertTimerTask(@Param("key") String key);

    void saveTimerTitle(@Param("taskId") long taskId, @Param("scope") String scope,
                        @Param("parentId") String parentId, @Param("title") String title);

    void saveChildDraft(@Param("taskId") long taskId, @Param("draft") String draft);

    String findChildDraft(@Param("taskId") long taskId, @Param("scope") String scope);

    List<PendingEffort> findPendingEfforts(@Param("scope") String scope);

    PendingEffort findPendingEffort(@Param("taskId") long taskId, @Param("day") String day);

    int pendingEffortCount();

    List<Long> pendingEffortTasks(@Param("day") String day);

    void completePendingEffort(@Param("effort") PendingEffort effort);

    int saveBinding(@Param("taskId") long taskId, @Param("binding") Binding binding);

    boolean hasCreatedTask(@Param("taskId") long taskId, @Param("binding") Binding binding);

    PendingWrite reserveWrite(@Param("taskId") long taskId, @Param("scope") String scope,
                              @Param("operation") String operation, @Param("marker") String marker);

    int completeWrite(@Param("marker") String marker, @Param("remoteId") String remoteId);

    void releaseWrite(@Param("marker") String marker);
}
