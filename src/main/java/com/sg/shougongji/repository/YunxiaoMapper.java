package com.sg.shougongji.repository;

import com.sg.shougongji.model.Yunxiao.Assignee;
import com.sg.shougongji.model.Yunxiao.Binding;
import com.sg.shougongji.model.Yunxiao.ConnectionSettings;
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

    int saveBinding(@Param("taskId") long taskId, @Param("binding") Binding binding);

    boolean hasCreatedTask(@Param("taskId") long taskId, @Param("binding") Binding binding);

    PendingWrite reserveWrite(@Param("taskId") long taskId, @Param("scope") String scope,
                              @Param("operation") String operation, @Param("marker") String marker);

    int completeWrite(@Param("marker") String marker, @Param("remoteId") String remoteId);

    void releaseWrite(@Param("marker") String marker);
}
