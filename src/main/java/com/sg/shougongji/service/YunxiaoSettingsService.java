package com.sg.shougongji.service;

import com.sg.shougongji.model.Yunxiao.ConnectionSettings;
import com.sg.shougongji.repository.YunxiaoRepository;
import com.sg.shougongji.repository.yunxiao.YunxiaoClient;

import java.sql.SQLException;

public final class YunxiaoSettingsService {
    private final YunxiaoRepository repository;
    private YunxiaoService currentConnection;

    public YunxiaoSettingsService(YunxiaoRepository repository) {
        this.repository = repository;
    }

    public ConnectionSettings readSettings() throws SQLException {
        return repository.findConnection();
    }

    public YunxiaoService openSavedConnection(ConnectionSettings saved) {
        try {
            return saved == null ? null : connect(saved);
        } catch (IllegalArgumentException invalid) {
            // 设置页可以打开并修正无效的旧配置。
            return null;
        }
    }

    public YunxiaoService currentConnection() throws SQLException {
        if (currentConnection == null) {
            currentConnection = openSavedConnection(readSettings());
        }
        return currentConnection;
    }

    public void saveConnection(String instance, String organization, String accessToken, ConnectionSettings saved)
            throws SQLException {
        String endpoint = instance.strip();
        String organizationId = organization.strip();
        String token = accessToken.strip();
        if (token.isEmpty() && saved != null) {
            if (!endpoint.equals(saved.instance()) || !organizationId.equals(saved.organizationId())) {
                throw new IllegalArgumentException("更换组织或实例时，请重新填写个人访问令牌。");
            }
            token = saved.accessToken();
        }
        ConnectionSettings updated = new ConnectionSettings(endpoint, organizationId, token);
        YunxiaoService connection = connect(updated);
        repository.saveConnection(updated);
        currentConnection = connection;
    }

    public void forgetConnection() throws SQLException {
        repository.deleteConnection();
        currentConnection = null;
    }

    private YunxiaoService connect(ConnectionSettings settings) {
        YunxiaoClient client = new YunxiaoClient(settings.instance(), settings.organizationId(), settings.accessToken());
        return new YunxiaoService(repository, client);
    }
}
