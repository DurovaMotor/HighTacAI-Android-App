from __future__ import annotations

from functools import lru_cache
import os
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


PACKAGE_ROOT = Path(__file__).resolve().parent
_SOURCE_SERVER_ROOT = PACKAGE_ROOT.parents[1]
SERVER_ROOT = (
    _SOURCE_SERVER_ROOT if (_SOURCE_SERVER_ROOT / "pyproject.toml").is_file() else PACKAGE_ROOT
)
DEFAULT_DATA_DIR = (
    SERVER_ROOT / "runtime"
    if SERVER_ROOT != PACKAGE_ROOT
    else Path(os.environ.get("PROGRAMDATA", Path.home() / ".hightac"))
    / "HighTac"
    / "Platform"
)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="HIGHTAC_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    environment: Literal["development", "test", "production"] = "development"
    host: str = "0.0.0.0"
    port: int = Field(default=8088, ge=1, le=65535)
    data_dir: Path = DEFAULT_DATA_DIR
    database_url: str | None = None
    auto_migrate: bool = True
    bootstrap_admin: bool = True
    bootstrap_admin_username: str = Field(default="Adam", min_length=1, max_length=80)
    bootstrap_admin_password: SecretStr | None = Field(
        default=None,
        min_length=1,
        exclude=True,
    )

    log_level: str = "INFO"
    log_to_file: bool = True
    log_max_bytes: int = 20 * 1024 * 1024
    log_backup_count: int = 10

    session_cookie_name: str = "hightac_session"
    session_ttl_seconds: int = 8 * 60 * 60
    session_cookie_secure: bool = False
    login_rate_limit_attempts: int = 8
    login_rate_limit_window_seconds: int = 5 * 60

    enrollment_ttl_seconds: int = 24 * 60 * 60
    device_namespace: str = "hightac.android.installation.identity.v1"

    mqtt_enabled: bool = True
    mqtt_required_for_ready: bool = False
    mqtt_host: str = "127.0.0.1"
    mqtt_port: int = Field(default=1884, ge=1, le=65535)
    mqtt_username: str | None = None
    mqtt_password: SecretStr | None = Field(default=None, exclude=True)
    mqtt_keepalive_seconds: int = 30
    mqtt_client_id: str = "hightac-platform-backend"
    command_scheduler_enabled: bool = True

    openai_api_key: SecretStr | None = Field(default=None, exclude=True)
    openai_base_url: str = "https://api.openai.com/v1"
    openai_model: str = Field(default="gpt-5.5", min_length=1, max_length=128)
    openai_timeout_seconds: float = Field(default=180.0, gt=0, le=300)
    jiandaoyun_api_key: SecretStr | None = Field(default=None, exclude=True)
    jiandaoyun_app_id: str | None = Field(default=None, max_length=128)
    jiandaoyun_entry_id: str | None = Field(default=None, max_length=128)
    jiandaoyun_base_url: str = "https://api.jiandaoyun.com/api"
    jiandaoyun_timeout_seconds: float = Field(default=30.0, gt=0, le=120)
    mobile_proxy_max_request_bytes: int = Field(
        default=8 * 1024 * 1024,
        ge=1024,
        le=32 * 1024 * 1024,
    )
    mobile_media_token_ttl_seconds: int = Field(default=5 * 60, ge=30, le=15 * 60)
    mobile_media_max_bytes: int = Field(
        default=10 * 1024 * 1024,
        ge=1024,
        le=32 * 1024 * 1024,
    )
    mobile_media_timeout_seconds: float = Field(default=15.0, gt=0, le=60)

    broker_mode: Literal["unmanaged", "subprocess", "windows_service"] = "unmanaged"
    broker_service_name: str = "HighTacMqttBroker"
    broker_command: list[str] = Field(default_factory=list)
    broker_pid_path: Path | None = (
        SERVER_ROOT.parent / "tools" / "mqtt" / "runtime" / "mosquitto.pid"
        if SERVER_ROOT != PACKAGE_ROOT
        else None
    )
    broker_log_path: Path | None = (
        SERVER_ROOT.parent / "tools" / "mqtt" / "runtime" / "log" / "mosquitto.log"
        if SERVER_ROOT != PACKAGE_ROOT
        else None
    )
    broker_control_timeout_seconds: int = 15

    backup_retention_days: int = 30
    operation_log_retention_days: int = 365
    lifecycle_scheduler_enabled: bool = True
    restore_quiesce_timeout_seconds: float = Field(default=15.0, gt=0, le=300)
    service_restart_delay_seconds: float = Field(default=1.0, ge=0.1, le=30)
    low_battery_percent: int = Field(default=30, ge=0, le=100)
    default_site_name: str = "HighTac Site"
    serve_web_spa: bool = False
    web_dist_dir: Path | None = (
        SERVER_ROOT.parent / "web" / "dist"
        if SERVER_ROOT != PACKAGE_ROOT
        else None
    )

    @field_validator("bootstrap_admin_username")
    @classmethod
    def normalize_bootstrap_admin_username(cls, value: str) -> str:
        username = value.strip()
        if not username:
            raise ValueError("bootstrap admin username must not be blank")
        return username

    @field_validator("openai_api_key", "jiandaoyun_api_key", mode="before")
    @classmethod
    def normalize_optional_api_key(cls, value: object) -> object:
        if isinstance(value, str) and not value.strip():
            return None
        return value

    @field_validator("jiandaoyun_app_id", "jiandaoyun_entry_id", mode="before")
    @classmethod
    def normalize_optional_upstream_id(cls, value: object) -> object:
        if isinstance(value, str):
            return value.strip() or None
        return value

    @field_validator("openai_model")
    @classmethod
    def normalize_openai_model(cls, value: str) -> str:
        model = value.strip()
        if not model:
            raise ValueError("OpenAI model must not be blank")
        return model

    @field_validator("openai_base_url", "jiandaoyun_base_url")
    @classmethod
    def normalize_upstream_base_url(cls, value: str) -> str:
        normalized = value.strip().rstrip("/")
        parsed = urlsplit(normalized)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.netloc
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError(
                "Upstream base URL must be an HTTP(S) origin/path without "
                "credentials, query, or fragment"
            )
        return normalized

    @model_validator(mode="after")
    def require_production_bootstrap_password(self) -> "Settings":
        if (
            self.environment == "production"
            and self.bootstrap_admin
            and self.bootstrap_admin_password is None
        ):
            raise ValueError(
                "HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD must be set when production "
                "bootstrap is enabled"
            )
        return self

    @property
    def database_path(self) -> Path:
        return self.data_dir / "db" / "hightac.db"

    @property
    def effective_database_url(self) -> str:
        if self.database_url:
            return self.database_url
        return f"sqlite:///{self.database_path.resolve().as_posix()}"

    @property
    def logs_dir(self) -> Path:
        return self.data_dir / "logs"

    @property
    def backups_dir(self) -> Path:
        return self.data_dir / "backups"

    @property
    def secrets_dir(self) -> Path:
        return self.data_dir / "secrets"

    @property
    def device_enrollment_key_path(self) -> Path:
        return self.secrets_dir / "device-enrollment.key"

    @property
    def platform_log_path(self) -> Path:
        return self.logs_dir / "platform.log"

    def ensure_runtime_directories(self) -> None:
        for directory in (
            self.data_dir,
            self.database_path.parent,
            self.logs_dir,
            self.backups_dir,
            self.secrets_dir,
        ):
            directory.mkdir(parents=True, exist_ok=True)


@lru_cache
def get_settings() -> Settings:
    return Settings()
