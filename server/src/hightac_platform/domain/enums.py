from enum import StrEnum


class ActorType(StrEnum):
    ADMIN = "ADMIN"
    ANDROID = "ANDROID"
    SYSTEM = "SYSTEM"


class DeviceStatus(StrEnum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    REVOKED = "REVOKED"


class EnrollmentStatus(StrEnum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    TOKEN_ISSUED = "TOKEN_ISSUED"
    REVOKED = "REVOKED"
    EXPIRED = "EXPIRED"


class StationStatus(StrEnum):
    UNKNOWN = "UNKNOWN"
    ONLINE = "ONLINE"
    STALE = "STALE"
    OFFLINE = "OFFLINE"


class ProductSource(StrEnum):
    BINDING = "BINDING"
    EXCEL = "EXCEL"
    WEB = "WEB"


class BindingSource(StrEnum):
    ANDROID = "ANDROID"
    WEB = "WEB"
    MIGRATION = "MIGRATION"


class AndroidBindingMigrationClassification(StrEnum):
    MIGRATABLE = "MIGRATABLE"
    IDENTICAL = "IDENTICAL"
    DUPLICATE_LEGACY_TAG = "DUPLICATE_LEGACY_TAG"
    TAG_BOUND_TO_DIFFERENT_PRODUCT = "TAG_BOUND_TO_DIFFERENT_PRODUCT"
    STATION_MISMATCH = "STATION_MISMATCH"
    STATION_NOT_FOUND = "STATION_NOT_FOUND"


class AndroidBindingMigrationOutcome(StrEnum):
    MIGRATED = "MIGRATED"
    IDENTICAL = "IDENTICAL"
    SKIPPED = "SKIPPED"


class CommandAction(StrEnum):
    LIGHT_ON = "LIGHT_ON"
    LIGHT_OFF = "LIGHT_OFF"
    ALL_OFF = "ALL_OFF"


class LightColor(StrEnum):
    RED = "RED"
    GREEN = "GREEN"
    BLUE = "BLUE"
    CYAN = "CYAN"
    PINK = "PINK"


class CommandStatus(StrEnum):
    ACCEPTED = "ACCEPTED"
    PUBLISHED = "PUBLISHED"
    CONFIRMED = "CONFIRMED"
    PARTIALLY_CONFIRMED = "PARTIALLY_CONFIRMED"
    UNCONFIRMED = "UNCONFIRMED"
    FAILED = "FAILED"
    SUPERSEDED = "SUPERSEDED"


class CommandItemStatus(StrEnum):
    PENDING = "PENDING"
    PUBLISHED = "PUBLISHED"
    CONFIRMED = "CONFIRMED"
    UNCONFIRMED = "UNCONFIRMED"
    FAILED = "FAILED"
    SUPERSEDED = "SUPERSEDED"


class ImportJobStatus(StrEnum):
    PREVIEWED = "PREVIEWED"
    INVALID = "INVALID"
    COMMITTED = "COMMITTED"


class BackupStatus(StrEnum):
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
