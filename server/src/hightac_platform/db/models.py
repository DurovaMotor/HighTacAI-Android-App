from __future__ import annotations

from sqlalchemy import (
    Boolean,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column

from hightac_platform.db.base import Base
from hightac_platform.domain.enums import (
    BackupStatus,
    CommandItemStatus,
    CommandStatus,
    DeviceStatus,
    EnrollmentStatus,
    ImportJobStatus,
    StationStatus,
)
from hightac_platform.utils import new_id, utc_ms


class AdminUser(Base):
    __tablename__ = "admin_users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    username: Mapped[str] = mapped_column(String(80), nullable=False, unique=True)
    password_hash: Mapped[str] = mapped_column(Text, nullable=False)
    must_change_password: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    last_login_at_ms: Mapped[int | None] = mapped_column(Integer)


class AdminSession(Base):
    __tablename__ = "admin_sessions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    admin_user_id: Mapped[str] = mapped_column(
        ForeignKey("admin_users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    token_hash: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    csrf_token_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    expires_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    last_seen_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    revoked_at_ms: Mapped[int | None] = mapped_column(Integer)
    client_ip: Mapped[str | None] = mapped_column(String(64))
    user_agent: Mapped[str | None] = mapped_column(String(512))


class AndroidDevice(Base):
    __tablename__ = "android_devices"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    fingerprint_hash: Mapped[str] = mapped_column(
        String(64), nullable=False, index=True
    )
    token_hash: Mapped[str | None] = mapped_column(String(64), unique=True)
    display_name: Mapped[str | None] = mapped_column(String(120))
    manufacturer: Mapped[str | None] = mapped_column(String(120))
    model: Mapped[str | None] = mapped_column(String(120))
    app_version: Mapped[str | None] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default=DeviceStatus.PENDING.value, index=True
    )
    first_seen_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    last_seen_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    approved_at_ms: Mapped[int | None] = mapped_column(Integer)
    revoked_at_ms: Mapped[int | None] = mapped_column(Integer)


class DeviceEnrollment(Base):
    __tablename__ = "device_enrollments"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    fingerprint_hash: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    install_secret_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    poll_secret_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    challenge_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    android_device_id: Mapped[str | None] = mapped_column(
        ForeignKey("android_devices.id", ondelete="SET NULL"), index=True
    )
    manufacturer: Mapped[str | None] = mapped_column(String(120))
    model: Mapped[str | None] = mapped_column(String(120))
    app_version: Mapped[str | None] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default=EnrollmentStatus.PENDING.value, index=True
    )
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    expires_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    approved_at_ms: Mapped[int | None] = mapped_column(Integer)
    token_issued_at_ms: Mapped[int | None] = mapped_column(Integer)


class Site(Base):
    __tablename__ = "sites"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    name: Mapped[str] = mapped_column(String(160), nullable=False)
    address: Mapped[str | None] = mapped_column(String(500))
    notes: Mapped[str | None] = mapped_column(Text)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    updated_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)


class Station(Base):
    __tablename__ = "stations"

    station_id: Mapped[str] = mapped_column(String(12), primary_key=True)
    site_id: Mapped[str] = mapped_column(ForeignKey("sites.id", ondelete="RESTRICT"), index=True)
    alias: Mapped[str | None] = mapped_column(String(160))
    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default=StationStatus.UNKNOWN.value, index=True
    )
    mac: Mapped[str | None] = mapped_column(String(64))
    firmware_version: Mapped[str | None] = mapped_column(String(64))
    server_address: Mapped[str | None] = mapped_column(String(255))
    heartbeat_seconds: Mapped[int] = mapped_column(Integer, nullable=False, default=20)
    last_heartbeat_at_ms: Mapped[int | None] = mapped_column(Integer, index=True)
    total_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    send_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    updated_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)


class LightTag(Base):
    __tablename__ = "light_tags"

    tag_id: Mapped[str] = mapped_column(String(12), primary_key=True)
    site_id: Mapped[str] = mapped_column(ForeignKey("sites.id", ondelete="RESTRICT"), index=True)
    station_id: Mapped[str | None] = mapped_column(
        ForeignKey("stations.station_id", ondelete="SET NULL"), index=True
    )
    registered_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    first_seen_at_ms: Mapped[int | None] = mapped_column(Integer)
    last_seen_at_ms: Mapped[int | None] = mapped_column(Integer, index=True)
    battery_raw: Mapped[int | None] = mapped_column(Integer)
    battery_voltage_mv: Mapped[int | None] = mapped_column(Integer)
    battery_level: Mapped[int | None] = mapped_column(Integer, index=True)
    firmware_version: Mapped[str | None] = mapped_column(String(64))
    group_no: Mapped[int | None] = mapped_column(Integer)
    last_result_type: Mapped[int | None] = mapped_column(Integer)
    is_abnormal: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False, index=True)
    abnormal_reason: Mapped[str | None] = mapped_column(String(500))
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    updated_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)


class Product(Base):
    __tablename__ = "products"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    product_code: Mapped[str] = mapped_column(String(128), nullable=False, unique=True, index=True)
    product_name: Mapped[str | None] = mapped_column(String(255))
    source: Mapped[str] = mapped_column(String(20), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True, index=True)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    updated_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)


class Binding(Base):
    __tablename__ = "bindings"
    __table_args__ = (
        UniqueConstraint(
            "actor_type", "actor_id", "idempotency_key", name="uq_bindings_actor_idempotency"
        ),
        Index(
            "uq_bindings_active_tag",
            "tag_id",
            unique=True,
            sqlite_where=text("is_active = 1"),
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    product_id: Mapped[str] = mapped_column(
        ForeignKey("products.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    tag_id: Mapped[str] = mapped_column(
        ForeignKey("light_tags.tag_id", ondelete="RESTRICT"), nullable=False, index=True
    )
    site_id: Mapped[str] = mapped_column(
        ForeignKey("sites.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    station_id: Mapped[str | None] = mapped_column(
        ForeignKey("stations.station_id", ondelete="SET NULL"), index=True
    )
    source: Mapped[str] = mapped_column(String(20), nullable=False, index=True)
    actor_type: Mapped[str] = mapped_column(String(20), nullable=False)
    actor_id: Mapped[str] = mapped_column(String(36), nullable=False)
    idempotency_key: Mapped[str | None] = mapped_column(String(128))
    request_hash: Mapped[str | None] = mapped_column(String(64))
    bound_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    unbound_at_ms: Mapped[int | None] = mapped_column(Integer)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True, index=True)


class Command(Base):
    __tablename__ = "commands"
    __table_args__ = (
        UniqueConstraint(
            "actor_type", "actor_id", "idempotency_key", name="uq_commands_actor_idempotency"
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    action: Mapped[str] = mapped_column(String(20), nullable=False)
    color: Mapped[str | None] = mapped_column(String(20))
    status: Mapped[str] = mapped_column(
        String(32), nullable=False, default=CommandStatus.ACCEPTED.value, index=True
    )
    target_type: Mapped[str] = mapped_column(String(20), nullable=False)
    target_value: Mapped[str] = mapped_column(String(255), nullable=False)
    actor_type: Mapped[str] = mapped_column(String(20), nullable=False)
    actor_id: Mapped[str] = mapped_column(String(36), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(128), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms, index=True)
    updated_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    completed_at_ms: Mapped[int | None] = mapped_column(Integer)
    error_message: Mapped[str | None] = mapped_column(String(500))


class CommandItem(Base):
    __tablename__ = "command_items"
    __table_args__ = (
        UniqueConstraint("command_id", "tag_id", name="uq_command_items_command_tag"),
        Index("ix_command_items_latest_tag", "tag_id", "created_at_ms"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    command_id: Mapped[str] = mapped_column(
        ForeignKey("commands.id", ondelete="CASCADE"), nullable=False, index=True
    )
    station_id: Mapped[str] = mapped_column(
        ForeignKey("stations.station_id", ondelete="RESTRICT"), nullable=False, index=True
    )
    tag_id: Mapped[str] = mapped_column(
        ForeignKey("light_tags.tag_id", ondelete="RESTRICT"), nullable=False, index=True
    )
    action: Mapped[str] = mapped_column(String(20), nullable=False)
    color: Mapped[str | None] = mapped_column(String(20))
    status: Mapped[str] = mapped_column(
        String(32), nullable=False, default=CommandItemStatus.PENDING.value, index=True
    )
    batch_no: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    retry_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    first_published_at_ms: Mapped[int | None] = mapped_column(Integer, index=True)
    last_published_at_ms: Mapped[int | None] = mapped_column(Integer)
    deadline_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    confirmed_at_ms: Mapped[int | None] = mapped_column(Integer)
    result_type: Mapped[int | None] = mapped_column(Integer)
    result_summary: Mapped[str | None] = mapped_column(Text)
    error_message: Mapped[str | None] = mapped_column(String(500))


class OperationLog(Base):
    __tablename__ = "operation_logs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    event_type: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    site_id: Mapped[str | None] = mapped_column(String(36), index=True)
    station_id: Mapped[str | None] = mapped_column(String(12), index=True)
    tag_id: Mapped[str | None] = mapped_column(String(12), index=True)
    product_id: Mapped[str | None] = mapped_column(String(36), index=True)
    command_id: Mapped[str | None] = mapped_column(String(36), index=True)
    actor_type: Mapped[str] = mapped_column(String(20), nullable=False, index=True)
    actor_id: Mapped[str | None] = mapped_column(String(36), index=True)
    actor_name: Mapped[str | None] = mapped_column(String(160))
    request_summary: Mapped[str | None] = mapped_column(Text)
    result_summary: Mapped[str | None] = mapped_column(Text)
    client_ip: Mapped[str | None] = mapped_column(String(64))
    device_model: Mapped[str | None] = mapped_column(String(255))
    failure_reason: Mapped[str | None] = mapped_column(String(500))
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms, index=True)


class ImportJob(Base):
    __tablename__ = "import_jobs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    filename: Mapped[str] = mapped_column(String(255), nullable=False)
    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default=ImportJobStatus.PREVIEWED.value, index=True
    )
    row_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    valid_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    error_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    actor_type: Mapped[str] = mapped_column(String(20), nullable=False)
    actor_id: Mapped[str] = mapped_column(String(36), nullable=False)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    committed_at_ms: Mapped[int | None] = mapped_column(Integer)


class ImportJobRow(Base):
    __tablename__ = "import_job_rows"
    __table_args__ = (UniqueConstraint("job_id", "row_number", name="uq_import_rows_job_row"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    job_id: Mapped[str] = mapped_column(
        ForeignKey("import_jobs.id", ondelete="CASCADE"), nullable=False, index=True
    )
    row_number: Mapped[int] = mapped_column(Integer, nullable=False)
    product_code: Mapped[str | None] = mapped_column(String(128))
    product_name: Mapped[str | None] = mapped_column(String(255))
    is_valid: Mapped[bool] = mapped_column(Boolean, nullable=False)
    error_message: Mapped[str | None] = mapped_column(String(500))


class AppSetting(Base):
    __tablename__ = "app_settings"

    key: Mapped[str] = mapped_column(String(100), primary_key=True)
    value_json: Mapped[str] = mapped_column(Text, nullable=False)
    updated_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)
    updated_by: Mapped[str | None] = mapped_column(String(36))


class IdempotencyRecord(Base):
    __tablename__ = "idempotency_records"
    __table_args__ = (
        UniqueConstraint(
            "actor_type",
            "actor_id",
            "method",
            "route",
            "idempotency_key",
            name="uq_idempotency_scope",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    actor_type: Mapped[str] = mapped_column(String(20), nullable=False)
    actor_id: Mapped[str] = mapped_column(String(128), nullable=False)
    method: Mapped[str] = mapped_column(String(10), nullable=False)
    route: Mapped[str] = mapped_column(String(255), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(36), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    response_status: Mapped[int] = mapped_column(Integer, nullable=False)
    response_json: Mapped[str] = mapped_column(Text, nullable=False)
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms)


class BackupRecord(Base):
    __tablename__ = "backup_records"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    filename: Mapped[str] = mapped_column(String(255), nullable=False, unique=True)
    file_size: Mapped[int | None] = mapped_column(Integer)
    sha256: Mapped[str | None] = mapped_column(String(64))
    schema_version: Mapped[str | None] = mapped_column(String(64))
    kind: Mapped[str] = mapped_column(String(30), nullable=False, default="MANUAL")
    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default=BackupStatus.RUNNING.value, index=True
    )
    error_message: Mapped[str | None] = mapped_column(String(500))
    created_at_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=utc_ms, index=True)
    completed_at_ms: Mapped[int | None] = mapped_column(Integer)
