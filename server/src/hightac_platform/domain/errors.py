from __future__ import annotations

from typing import Any


class DomainError(Exception):
    status_code = 400
    code = "BAD_REQUEST"

    def __init__(
        self,
        message: str,
        *,
        code: str | None = None,
        details: list[dict[str, Any]] | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.code = code or type(self).code
        self.details = details or []


class BadRequestError(DomainError):
    status_code = 400
    code = "BAD_REQUEST"


class ValidationError(DomainError):
    status_code = 422
    code = "VALIDATION_ERROR"


class UnauthorizedError(DomainError):
    status_code = 401
    code = "AUTHENTICATION_REQUIRED"


class ForbiddenError(DomainError):
    status_code = 403
    code = "FORBIDDEN"


class PasswordChangeRequiredError(ForbiddenError):
    code = "PASSWORD_CHANGE_REQUIRED"


class NotFoundError(DomainError):
    status_code = 404
    code = "NOT_FOUND"


class GoneError(DomainError):
    status_code = 410
    code = "RESOURCE_GONE"


class ConflictError(DomainError):
    status_code = 409
    code = "CONFLICT"


class IdempotencyConflictError(ConflictError):
    code = "IDEMPOTENCY_KEY_REUSED"


class MigrationPreviewMismatchError(ConflictError):
    code = "MIGRATION_PREVIEW_MISMATCH"


class MigrationPreviewStaleError(ConflictError):
    code = "MIGRATION_PREVIEW_STALE"


class InvalidMigrationSelectionError(ValidationError):
    code = "INVALID_DUPLICATE_SELECTION"


class PayloadTooLargeError(DomainError):
    status_code = 413
    code = "PAYLOAD_TOO_LARGE"


class UnsupportedMediaTypeError(DomainError):
    status_code = 415
    code = "UNSUPPORTED_MEDIA_TYPE"


class TooManyRequestsError(DomainError):
    status_code = 429
    code = "RATE_LIMIT_EXCEEDED"


class ServiceUnavailableError(DomainError):
    status_code = 503
    code = "SERVICE_UNAVAILABLE"


class InsufficientStorageError(DomainError):
    status_code = 507
    code = "INSUFFICIENT_STORAGE"
