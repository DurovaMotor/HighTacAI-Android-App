from __future__ import annotations

import json
from typing import Any

from sqlalchemy.orm import Session

from hightac_platform.config import Settings
from hightac_platform.db.models import AppSetting
from hightac_platform.utils import utc_ms


class AppSettingsService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    def get(self, session: Session, key: str, default: Any = None) -> Any:
        setting = session.get(AppSetting, key)
        if setting is None:
            return default
        try:
            return json.loads(setting.value_json)
        except json.JSONDecodeError:
            return default

    def set(self, session: Session, key: str, value: Any, actor_id: str) -> AppSetting:
        setting = session.get(AppSetting, key)
        if setting is None:
            setting = AppSetting(key=key, value_json="null")
            session.add(setting)
        setting.value_json = json.dumps(value, ensure_ascii=True)
        setting.updated_at_ms = utc_ms()
        setting.updated_by = actor_id
        return setting

    def low_battery_percent(self, session: Session) -> int:
        value = self.get(session, "low_battery_percent", self.settings.low_battery_percent)
        return value if isinstance(value, int) and 0 <= value <= 100 else self.settings.low_battery_percent
