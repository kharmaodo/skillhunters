CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    issuer VARCHAR(500) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (issuer, subject)
);
CREATE TABLE global_role (
    user_id UUID NOT NULL REFERENCES app_user(id),
    role VARCHAR(30) NOT NULL CHECK (role IN ('ADMIN', 'COMPLIANCE')),
    PRIMARY KEY (user_id, role)
);
CREATE TABLE talent_pool (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL
);
CREATE TABLE membership (
    user_id UUID NOT NULL REFERENCES app_user(id),
    pool_id UUID NOT NULL REFERENCES talent_pool(id),
    role VARCHAR(30) NOT NULL CHECK (role IN ('RECRUITER','RECRUITMENT_LEAD')),
    PRIMARY KEY (user_id, pool_id, role)
);
CREATE TABLE identity_audit (
    id UUID PRIMARY KEY,
    actor_id UUID REFERENCES app_user(id),
    action VARCHAR(80) NOT NULL,
    target_id UUID,
    pool_id UUID,
    before_roles VARCHAR(100),
    after_roles VARCHAR(100),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX membership_pool_idx ON membership(pool_id, user_id);
