import json

from sqlalchemy import select

from hightac_platform.auth.context import Actor
from hightac_platform.db.models import Binding, Command, CommandItem, LightTag, Product
from hightac_platform.domain.enums import (
    ActorType,
    CommandAction,
    CommandItemStatus,
    CommandStatus,
    LightColor,
)

from conftest import seed_online_station_and_tags


def _bind_product(harness, product_code: str, tag_ids: list[str]) -> Product:
    with harness.runtime.database.session_factory() as session:
        product = Product(product_code=product_code, source="BINDING")
        session.add(product)
        session.flush()
        tags = {
            tag.tag_id: tag
            for tag in session.scalars(select(LightTag).where(LightTag.tag_id.in_(tag_ids)))
        }
        for tag_id in tag_ids:
            tag = tags[tag_id]
            session.add(
                Binding(
                    product_id=product.id,
                    tag_id=tag.tag_id,
                    site_id=tag.site_id,
                    station_id=tag.station_id,
                    source="WEB",
                    actor_type="ADMIN",
                    actor_id="seed",
                )
            )
        session.commit()
        return product


def test_command_batches_fixed_protocol_and_idempotency(harness) -> None:
    tag_ids = seed_online_station_and_tags(harness, 45)
    _bind_product(harness, "PRODUCT-45", tag_ids)
    auth = harness.login()
    headers = harness.mutation_headers(
        auth, "2ec5fd45-0e4d-4be4-a6bd-3517118b64df"
    )
    body = {
        "action": "LIGHT_ON",
        "product_code": "PRODUCT-45",
        "color": "CYAN",
    }
    response = harness.client.post("/api/v1/light-commands", headers=headers, json=body)
    assert response.status_code == 202, response.text
    assert response.json()["status"] == CommandStatus.PUBLISHED.value
    assert response.json()["requested_color"] == "CYAN"
    assert response.json()["target_count"] == 45
    assert [len(json.loads(payload)["Items"]) for _, payload in harness.publisher.messages] == [
        20,
        20,
        5,
    ]
    for topic, payload in harness.publisher.messages:
        assert topic == "/estation/90A9F1234567/task"
        task = json.loads(payload)
        assert task["Time"] == 1
        assert all(item["Beep"] is True and item["Flashing"] is True for item in task["Items"])
        assert all(
            item["Colors"] == [{"R": False, "G": True, "B": True}]
            for item in task["Items"]
        )

    repeated = harness.client.post("/api/v1/light-commands", headers=headers, json=body)
    assert repeated.status_code == 202
    assert repeated.json() == response.json()
    assert len(harness.publisher.messages) == 3


def test_retry_timeout_and_latest_command_wins(harness) -> None:
    tag_id = seed_online_station_and_tags(harness, 1)[0]
    actor = Actor(ActorType.ADMIN, "admin-test", "Test Admin")
    now = 1_000_000
    with harness.runtime.database.session_factory() as session:
        first, _ = harness.runtime.command_service.create(
            session,
            actor,
            action=CommandAction.LIGHT_ON,
            color=LightColor.RED,
            product_code=None,
            tag_ids=[tag_id],
            station_id=None,
            idempotency_key="first",
            now_ms=now,
        )
        first_id = first.id
    assert len(harness.publisher.messages) == 1

    with harness.runtime.database.session_factory() as session:
        harness.runtime.command_service.tick(session, now_ms=now + 2_001)
    assert len(harness.publisher.messages) == 2
    with harness.runtime.database.session_factory() as session:
        first_item = session.scalar(select(CommandItem).where(CommandItem.command_id == first_id))
        assert first_item.retry_count == 1
        assert first_item.status == CommandItemStatus.PUBLISHED.value

    with harness.runtime.database.session_factory() as session:
        second, _ = harness.runtime.command_service.create(
            session,
            actor,
            action=CommandAction.LIGHT_OFF,
            color=None,
            product_code=None,
            tag_ids=[tag_id],
            station_id=None,
            idempotency_key="second",
            now_ms=now + 3_000,
        )
        second_id = second.id
    with harness.runtime.database.session_factory() as session:
        old_command = session.get(Command, first_id)
        old_item = session.scalar(select(CommandItem).where(CommandItem.command_id == first_id))
        assert old_item.status == CommandItemStatus.SUPERSEDED.value
        assert old_command.status == CommandStatus.SUPERSEDED.value

    with harness.runtime.database.session_factory() as session:
        harness.runtime.command_service.tick(session, now_ms=now + 13_001)
    with harness.runtime.database.session_factory() as session:
        new_command = session.get(Command, second_id)
        new_item = session.scalar(select(CommandItem).where(CommandItem.command_id == second_id))
        assert new_item.status == CommandItemStatus.UNCONFIRMED.value
        assert new_command.status == CommandStatus.UNCONFIRMED.value
