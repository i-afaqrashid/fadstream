-- FadStream control-plane schema
-- Applied automatically by the postgres container on first boot.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- A registered Android device. Each owns exactly one stream path: devices/<id>.
CREATE TABLE devices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT        NOT NULL,
    -- Per-device secret (bcrypt/argon hash). Used to authenticate the device
    -- to both the control-plane (JWT issuance) and MediaMTX (publish auth).
    -- NEVER a shared key -> revoking one device cannot affect others.
    secret_hash     TEXT        NOT NULL,
    -- Stream key MediaMTX checks on publish; rotatable without re-enrolling.
    stream_key      TEXT        NOT NULL UNIQUE,
    enrolled_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ,
    -- live | offline | streaming | error
    status          TEXT        NOT NULL DEFAULT 'offline',
    -- last reported transport: whip | srt | rtmp
    transport       TEXT,
    revoked         BOOLEAN     NOT NULL DEFAULT false
);

CREATE INDEX idx_devices_status ON devices (status);

-- Commands queued for a device (start/stop/restart/setQuality/rotate...).
-- Delivered over the WSS control bus; persisted so they survive reconnects.
CREATE TABLE commands (
    id           BIGSERIAL PRIMARY KEY,
    device_id    UUID        NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    type         TEXT        NOT NULL,
    payload      JSONB       NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    acked_at     TIMESTAMPTZ
);

CREATE INDEX idx_commands_undelivered
    ON commands (device_id) WHERE delivered_at IS NULL;

-- Index of server-side recordings (files live on disk / object storage).
CREATE TABLE recordings (
    id          BIGSERIAL PRIMARY KEY,
    device_id   UUID        NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    path        TEXT        NOT NULL,          -- storage key / file path
    started_at  TIMESTAMPTZ NOT NULL,
    ended_at    TIMESTAMPTZ,
    bytes       BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_recordings_device_time ON recordings (device_id, started_at DESC);

-- Append-only audit trail. Covert-surveillance tools hide what they do;
-- a legitimate one records it. This is a deliberate design choice.
CREATE TABLE audit_log (
    id         BIGSERIAL PRIMARY KEY,
    device_id  UUID REFERENCES devices(id) ON DELETE SET NULL,
    actor      TEXT        NOT NULL,           -- 'device:<id>' | 'admin' | 'mediamtx'
    action     TEXT        NOT NULL,
    detail     JSONB       NOT NULL DEFAULT '{}',
    at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
