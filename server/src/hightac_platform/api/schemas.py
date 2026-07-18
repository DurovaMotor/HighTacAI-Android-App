from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from hightac_platform.domain.enums import CommandAction, LightColor
from hightac_platform.utils import (
    normalize_product_code,
    normalize_station_id,
    normalize_tag_id,
)


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class LoginRequest(StrictModel):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=1024)


class ChangePasswordRequest(StrictModel):
    current_password: str = Field(min_length=1, max_length=1024)
    new_password: str = Field(min_length=12, max_length=1024)


class EnrollmentCreateRequest(StrictModel):
    fingerprint_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    installation_key_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    manufacturer: str = Field(min_length=1, max_length=128)
    model: str = Field(min_length=1, max_length=128)
    app_version: str = Field(min_length=1, max_length=64)


class DeviceApproveRequest(StrictModel):
    display_name: str = Field(min_length=1, max_length=128)


class DeviceRenameRequest(StrictModel):
    display_name: str = Field(min_length=1, max_length=128)


class StationCreateRequest(StrictModel):
    station_id: str = Field(pattern=r"^90A9F[0-9A-F]{7}$")
    site_id: str = Field(pattern=r"^[0-9a-fA-F-]{36}$")
    alias: str | None = Field(default=None, max_length=128)


class StationPatchRequest(StrictModel):
    alias: str | None = Field(default=None, max_length=128)

    @model_validator(mode="after")
    def require_property(self) -> "StationPatchRequest":
        if not self.model_fields_set:
            raise ValueError("At least one property is required.")
        return self


class TagRegisterRequest(StrictModel):
    tag_id: str = Field(pattern=r"^AD1[0-9A-F]{9}$")
    station_id: str = Field(pattern=r"^90A9F[0-9A-F]{7}$")


class ProductCreateRequest(StrictModel):
    product_code: str = Field(min_length=1, max_length=128)
    product_name: str | None = Field(default=None, max_length=256)


class ProductPatchRequest(StrictModel):
    product_name: str | None = Field(default=None, max_length=256)
    is_active: bool | None = None

    @model_validator(mode="after")
    def require_property(self) -> "ProductPatchRequest":
        if not self.model_fields_set:
            raise ValueError("At least one property is required.")
        return self


class BindingCreateRequest(StrictModel):
    product_code: str = Field(min_length=1, max_length=128)
    product_name: str | None = Field(default=None, max_length=256)
    tag_id: str = Field(pattern=r"^AD1[0-9A-F]{9}$")
    station_id: str = Field(pattern=r"^90A9F[0-9A-F]{7}$")


class RebindRequest(StrictModel):
    product_code: str = Field(min_length=1, max_length=128)
    product_name: str | None = Field(default=None, max_length=256)
    expected_tag_id: str = Field(pattern=r"^AD1[0-9A-F]{9}$")


ClientRecordKey = Annotated[
    str,
    Field(min_length=1, max_length=256, pattern=r"^\S(?:.*\S)?$"),
]


class AndroidBindingMigrationRecord(StrictModel):
    client_record_key: ClientRecordKey
    product_code: str = Field(min_length=1, max_length=128)
    product_name: str | None = Field(default=None, max_length=256)
    tag_id: str = Field(min_length=9, max_length=12)
    station_id: str = Field(min_length=12, max_length=12)

    @field_validator("product_code")
    @classmethod
    def normalize_product(cls, value: str) -> str:
        return normalize_product_code(value)

    @field_validator("tag_id")
    @classmethod
    def normalize_tag(cls, value: str) -> str:
        return normalize_tag_id(value)

    @field_validator("station_id")
    @classmethod
    def normalize_station(cls, value: str) -> str:
        return normalize_station_id(value)

    @field_validator("product_name")
    @classmethod
    def normalize_product_name(cls, value: str | None) -> str | None:
        return value or None


class AndroidBindingMigrationPreviewRequest(StrictModel):
    records: list[AndroidBindingMigrationRecord] = Field(
        min_length=1,
        max_length=2000,
    )

    @model_validator(mode="after")
    def require_unique_record_keys(self) -> "AndroidBindingMigrationPreviewRequest":
        keys = [record.client_record_key for record in self.records]
        if len(keys) != len(set(keys)):
            raise ValueError("client_record_key values must be unique.")
        return self


class AndroidBindingMigrationCommitRequest(
    AndroidBindingMigrationPreviewRequest
):
    preview_token: str = Field(
        min_length=32,
        max_length=2048,
        pattern=r"^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$",
    )
    selected_duplicate_keys: list[ClientRecordKey] = Field(max_length=2000)

    @model_validator(mode="after")
    def require_unique_selected_keys(self) -> "AndroidBindingMigrationCommitRequest":
        if len(self.selected_duplicate_keys) != len(
            set(self.selected_duplicate_keys)
        ):
            raise ValueError("selected_duplicate_keys must contain unique values.")
        return self


class LightCommandRequest(StrictModel):
    action: CommandAction
    product_code: str | None = Field(default=None, max_length=128)
    tag_ids: list[
        Annotated[str, Field(pattern=r"^AD1[0-9A-F]{9}$")]
    ] | None = Field(default=None, min_length=1, max_length=2000)
    color: LightColor | None = None

    @model_validator(mode="after")
    def validate_command(self) -> "LightCommandRequest":
        if self.action is CommandAction.ALL_OFF:
            raise ValueError("Use the station all-off endpoint for ALL_OFF commands.")
        if bool(self.product_code) == bool(self.tag_ids):
            raise ValueError("Specify exactly one of product_code or tag_ids.")
        if self.action is CommandAction.LIGHT_ON and self.color is None:
            raise ValueError("LIGHT_ON requires color.")
        if self.action is CommandAction.LIGHT_OFF and self.color is not None:
            raise ValueError("LIGHT_OFF does not accept color.")
        if self.tag_ids and len(set(self.tag_ids)) != len(self.tag_ids):
            raise ValueError("tag_ids must contain unique values.")
        return self


class AllOffRequest(StrictModel):
    confirmation: Literal["ALL OFF"] | None = None


class SiteSettingsPatchRequest(StrictModel):
    name: str | None = Field(default=None, min_length=1, max_length=128)
    address: str | None = Field(default=None, max_length=512)
    notes: str | None = Field(default=None, max_length=2000)
    low_battery_threshold: Literal[0, 10, 30, 60, 80, 90, 100] | None = None

    @model_validator(mode="after")
    def require_property(self) -> "SiteSettingsPatchRequest":
        if not self.model_fields_set:
            raise ValueError("At least one property is required.")
        return self


class NetworkSettingsPatchRequest(StrictModel):
    api_bind_address: str | None = Field(default=None, min_length=1, max_length=255)
    api_port: int | None = Field(default=None, ge=1, le=65535)
    mqtt_host: str | None = Field(default=None, min_length=1, max_length=255)
    mqtt_port: int | None = Field(default=None, ge=1, le=65535)
    mqtt_tls_enabled: bool | None = None

    @model_validator(mode="after")
    def require_property(self) -> "NetworkSettingsPatchRequest":
        if not self.model_fields_set:
            raise ValueError("At least one property is required.")
        return self


class ConfirmationRequest(StrictModel):
    confirmation: str = Field(min_length=1, max_length=64)


class RestoreBackupRequest(StrictModel):
    confirmation: Literal["RESTORE BACKUP"]
    expected_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class ErrorResponse(BaseModel):
    error: dict[str, Any]
