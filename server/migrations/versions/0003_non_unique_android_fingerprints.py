"""treat Android fingerprints as non-unique audit signals

Revision ID: 0003
Revises: 0002
Create Date: 2026-07-16
"""

from collections.abc import Sequence

from alembic import op


revision: str = "0003"
down_revision: str | Sequence[str] | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

NAMING_CONVENTION = {
    "uq": "uq_%(table_name)s_%(column_0_name)s",
}


def upgrade() -> None:
    with op.batch_alter_table(
        "android_devices",
        recreate="always",
        naming_convention=NAMING_CONVENTION,
    ) as batch_op:
        batch_op.drop_constraint(
            "uq_android_devices_fingerprint_hash",
            type_="unique",
        )
        batch_op.create_index(
            "ix_android_devices_fingerprint_hash",
            ["fingerprint_hash"],
            unique=False,
        )


def downgrade() -> None:
    with op.batch_alter_table(
        "android_devices",
        recreate="always",
        naming_convention=NAMING_CONVENTION,
    ) as batch_op:
        batch_op.drop_index("ix_android_devices_fingerprint_hash")
        batch_op.create_unique_constraint(
            "uq_android_devices_fingerprint_hash",
            ["fingerprint_hash"],
        )
