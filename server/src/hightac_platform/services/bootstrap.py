from sqlalchemy import func, select
from sqlalchemy.orm import Session, sessionmaker

from hightac_platform.auth.security import hash_password
from hightac_platform.config import Settings
from hightac_platform.db.models import AdminUser, AppSetting, Site


def bootstrap_first_run(session_factory: sessionmaker[Session], settings: Settings) -> None:
    with session_factory.begin() as session:
        if session.get(AppSetting, "bootstrap.completed") is not None:
            return
        admin_count = session.scalar(select(func.count()).select_from(AdminUser)) or 0
        if admin_count == 0:
            bootstrap_password = settings.bootstrap_admin_password
            if bootstrap_password is None:
                raise RuntimeError(
                    "HIGHTAC_BOOTSTRAP_ADMIN_PASSWORD is required to create the "
                    "bootstrap administrator"
                )
            session.add(
                AdminUser(
                    username=settings.bootstrap_admin_username,
                    password_hash=hash_password(
                        bootstrap_password.get_secret_value()
                    ),
                    must_change_password=True,
                )
            )

        site_count = session.scalar(select(func.count()).select_from(Site)) or 0
        if site_count == 0:
            session.add(Site(name=settings.default_site_name))
        session.add(AppSetting(key="bootstrap.completed", value_json="true", updated_by="system"))
