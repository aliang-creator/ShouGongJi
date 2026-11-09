package com.sg.shougongji.service;

import com.sg.shougongji.AppServices;
import com.sg.shougongji.model.Yunxiao.Assignee;
import com.sg.shougongji.model.Yunxiao.ConnectionSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class YunxiaoSettingsServiceTest {
    @TempDir
    Path directory;

    @Test
    void remembersReplacesAndForgetsYunxiaoConnectionAcrossReopens() throws Exception {
        AppServices services = open();
        YunxiaoSettingsService settings = services.yunxiaoSettings();
        assertNull(settings.readSettings());
        services.timer().start("保留事项");

        ConnectionSettings central = new ConnectionSettings("", "example-org", "mock-credential-1");
        settings.saveConnection(central.instance(), central.organizationId(), central.accessToken(), null);
        settings = open().yunxiaoSettings();
        assertEquals(central, settings.readSettings());
        assertFalse(central.toString().contains(central.accessToken()));
        if (Files.getFileAttributeView(database(), PosixFileAttributeView.class) != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(database()));
        }

        settings.saveConnection(central.instance(), central.organizationId(), "", central);
        assertEquals(central, settings.readSettings());
        YunxiaoSettingsService currentSettings = settings;
        assertThrows(IllegalArgumentException.class,
                () -> currentSettings.saveConnection("", "different-org", "", central));
        assertEquals(central, settings.readSettings());

        ConnectionSettings region = new ConnectionSettings("https://projex.example.test", "", "mock-credential-2");
        settings.saveConnection(region.instance(), region.organizationId(), region.accessToken(), central);
        settings = open().yunxiaoSettings();
        assertEquals(region, settings.readSettings());

        settings.forgetConnection();
        assertNull(settings.currentConnection());
        services = open();
        assertNull(services.yunxiaoSettings().readSettings());
        assertEquals("保留事项", services.timer().active().task().title());
    }

    @Test
    void remembersDefaultAssigneesSeparatelyForEachYunxiaoConnection() throws Exception {
        ConnectionSettings organization = new ConnectionSettings("", "example-org", "mock-credential-1");
        ConnectionSettings instance = new ConnectionSettings("https://projex.example.test", "", "mock-credential-2");
        Assignee first = new Assignee("member-1", "王小明");
        Assignee second = new Assignee("member-2", "王小明");

        YunxiaoSettingsService settings = open().yunxiaoSettings();
        YunxiaoService organizationConnection = settings.openSavedConnection(organization);
        YunxiaoService instanceConnection = settings.openSavedConnection(instance);
        assertNull(organizationConnection.defaultAssignee());
        organizationConnection.saveDefaultAssignee(first);
        assertNull(instanceConnection.defaultAssignee());
        instanceConnection.saveDefaultAssignee(second);

        settings = open().yunxiaoSettings();
        organizationConnection = settings.openSavedConnection(organization);
        instanceConnection = settings.openSavedConnection(instance);
        assertEquals(first, organizationConnection.defaultAssignee());
        assertEquals(second, instanceConnection.defaultAssignee());
        organizationConnection.saveDefaultAssignee(second);

        settings = open().yunxiaoSettings();
        assertEquals(second, settings.openSavedConnection(organization).defaultAssignee());
        assertEquals(second, settings.openSavedConnection(instance).defaultAssignee());
    }

    @Test
    void allowsSettingAnAssigneeAfterTheLegacyProjectDefaultWasSaved() throws Exception {
        ConnectionSettings organization = new ConnectionSettings("", "example-org", "mock-credential");
        YunxiaoService yunxiao = open().yunxiaoSettings().openSavedConnection(organization);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO yunxiao_assignee_preference (scope, member_id, name) VALUES (?, '', '')")) {
            statement.setString(1, yunxiao.scope());
            statement.executeUpdate();
        }
        assertNull(yunxiao.defaultAssignee());
        assertThrows(IllegalArgumentException.class, () -> yunxiao.saveDefaultAssignee(new Assignee("", "")));

        Assignee assignee = new Assignee("member-1", "王小明");
        yunxiao.saveDefaultAssignee(assignee);
        assertEquals(assignee, open().yunxiaoSettings().openSavedConnection(organization).defaultAssignee());
    }

    private Path database() {
        return directory.resolve("worklog.sqlite");
    }

    private AppServices open() throws Exception {
        return AppServices.open(database(), Clock.systemUTC());
    }
}
