package com.sg.shougongji;

import com.sg.shougongji.repository.TimerRepository;
import com.sg.shougongji.repository.WorkLogDatabase;
import com.sg.shougongji.repository.YunxiaoRepository;
import com.sg.shougongji.service.ReviewService;
import com.sg.shougongji.service.TimerService;
import com.sg.shougongji.service.YunxiaoAutoSync;
import com.sg.shougongji.service.YunxiaoSettingsService;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;

/** 应用启动时组装业务服务，本地仓储共用同一个数据源。 */
public record AppServices(TimerService timer, ReviewService review, YunxiaoSettingsService yunxiaoSettings,
                          YunxiaoAutoSync yunxiaoAutoSync) {
    public static AppServices open(Path databaseFile, Clock clock) throws SQLException {
        WorkLogDatabase database = new WorkLogDatabase(databaseFile);
        TimerRepository timer = new TimerRepository(database);
        YunxiaoRepository yunxiao = new YunxiaoRepository(database);
        ReviewService review = new ReviewService(timer, yunxiao, clock.getZone());
        return new AppServices(new TimerService(timer, clock), review, new YunxiaoSettingsService(yunxiao),
                new YunxiaoAutoSync(yunxiao, review));
    }
}
