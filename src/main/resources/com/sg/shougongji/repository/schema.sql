CREATE TABLE IF NOT EXISTS task (
    id INTEGER PRIMARY KEY,
    title TEXT NOT NULL UNIQUE,
    work_item TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS work_session (
    id INTEGER PRIMARY KEY,
    task_id INTEGER NOT NULL REFERENCES task(id),
    started_at INTEGER NOT NULL,
    ended_at INTEGER CHECK (ended_at > started_at),
    note TEXT NOT NULL DEFAULT '',
    manual INTEGER NOT NULL DEFAULT 0,
    estimated INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS one_active_session
ON work_session ((1)) WHERE ended_at IS NULL;

CREATE INDEX IF NOT EXISTS session_time ON work_session (started_at);

CREATE TABLE IF NOT EXISTS work_schedule (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    morning_start INTEGER NOT NULL,
    morning_end INTEGER NOT NULL,
    afternoon_start INTEGER NOT NULL,
    afternoon_end INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS scheduled_session (
    session_id INTEGER PRIMARY KEY REFERENCES work_session(id) ON DELETE CASCADE,
    day TEXT NOT NULL,
    position INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS scheduled_session_day ON scheduled_session (day, position);

CREATE TABLE IF NOT EXISTS hidden_recent_task (
    task_id INTEGER PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS yunxiao_binding (
    task_id INTEGER PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    scope TEXT NOT NULL,
    child_id TEXT NOT NULL,
    label TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS yunxiao_timer_task (
    task_id INTEGER PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    scope TEXT NOT NULL,
    parent_id TEXT,
    display_title TEXT NOT NULL,
    UNIQUE (scope, parent_id, display_title)
);

CREATE TABLE IF NOT EXISTS yunxiao_auto_task (
    task_id INTEGER PRIMARY KEY REFERENCES yunxiao_timer_task(task_id) ON DELETE CASCADE,
    draft TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS yunxiao_auto_effort (
    task_id INTEGER NOT NULL REFERENCES yunxiao_auto_task(task_id) ON DELETE CASCADE,
    day TEXT NOT NULL,
    revision TEXT NOT NULL,
    PRIMARY KEY (task_id, day)
);

CREATE TABLE IF NOT EXISTS yunxiao_connection (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    instance TEXT NOT NULL,
    organization_id TEXT NOT NULL,
    access_token TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS yunxiao_assignee_preference (
    scope TEXT PRIMARY KEY,
    member_id TEXT NOT NULL,
    name TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS yunxiao_write (
    task_id INTEGER NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    scope TEXT NOT NULL,
    operation TEXT NOT NULL,
    marker TEXT NOT NULL,
    remote_id TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (task_id, scope, operation)
);
