import json
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from time import perf_counter

from sqlalchemy import event, func, select

from hightac_platform.auth.context import Actor
from hightac_platform.db.models import (
    Binding,
    Command,
    CommandItem,
    LightTag,
    Product,
)
from hightac_platform.domain.enums import (
    ActorType,
    CommandAction,
    CommandItemStatus,
    CommandStatus,
    LightColor,
)

from conftest import seed_online_station_and_tags


TAG_COUNT = 2_000
CLIENT_COUNT = 10
STATION_ID = "90A9F1234567"


def _bind_capacity_product(harness, tag_ids: list[str]) -> Product:
    with harness.runtime.database.session_factory() as session:
        product = Product(
            product_code="CAPACITY-2000",
            source="BINDING",
        )
        session.add(product)
        session.flush()
        tags = {
            tag.tag_id: tag
            for tag in session.scalars(
                select(LightTag).where(LightTag.tag_id.in_(tag_ids))
            )
        }
        session.add_all(
            Binding(
                product_id=product.id,
                tag_id=tag.tag_id,
                site_id=tag.site_id,
                station_id=tag.station_id,
                source="WEB",
                actor_type="ADMIN",
                actor_id="capacity-seed",
            )
            for tag in tags.values()
        )
        session.commit()
        return product


def _result_payload(tag_ids: list[str]) -> bytes:
    return json.dumps(
        {
            "ID": STATION_ID,
            "TotalCount": len(tag_ids),
            "SendCount": 0,
            "Results": [
                {
                    "TagID": tag_id,
                    "ResultType": 254,
                    "Battery": 29,
                    "Colors": [{"R": False, "G": True, "B": True}],
                    "Sequence": index,
                }
                for index, tag_id in enumerate(tag_ids)
            ],
        },
        separators=(",", ":"),
    ).encode()


def test_first_release_mqtt_capacity_envelope(harness) -> None:
    setup_started = perf_counter()
    tag_ids = seed_online_station_and_tags(harness, TAG_COUNT)
    _bind_capacity_product(harness, tag_ids)
    setup_ms = (perf_counter() - setup_started) * 1000

    actor = Actor(ActorType.ADMIN, "capacity-test", "Capacity Test")
    command_started = perf_counter()
    with harness.runtime.database.session_factory() as session:
        command, created = harness.runtime.command_service.create(
            session,
            actor,
            action=CommandAction.LIGHT_ON,
            color=LightColor.CYAN,
            product_code="CAPACITY-2000",
            tag_ids=None,
            station_id=None,
            idempotency_key="capacity-command",
        )
        command_id = command.id
    command_ms = (perf_counter() - command_started) * 1000

    assert created is True
    assert len(harness.publisher.messages) == 100
    packet_sizes = [
        len(json.loads(payload)["Items"])
        for _topic, payload in harness.publisher.messages
    ]
    assert packet_sizes == [20] * 100
    assert {topic for topic, _payload in harness.publisher.messages} == {
        f"/estation/{STATION_ID}/task"
    }
    with harness.runtime.database.session_factory() as session:
        assert session.scalar(select(func.count()).select_from(Binding)) == TAG_COUNT

    statements = {"select": 0, "total": 0}

    def count_statement(
        _connection,
        _cursor,
        statement,
        _parameters,
        _context,
        _executemany,
    ) -> None:
        statements["total"] += 1
        if statement.lstrip().upper().startswith("SELECT"):
            statements["select"] += 1

    engine = harness.runtime.database.engine
    event.listen(engine, "before_cursor_execute", count_statement)
    telemetry_started = perf_counter()
    try:
        accepted = harness.runtime.telemetry_service.handle_message(
            f"/estation/{STATION_ID}/result",
            _result_payload(tag_ids),
        )
    finally:
        telemetry_ms = (perf_counter() - telemetry_started) * 1000
        event.remove(engine, "before_cursor_execute", count_statement)

    assert accepted is True
    assert statements["select"] <= 12
    assert statements["total"] <= 20
    assert telemetry_ms < 10_000
    with harness.runtime.database.session_factory() as session:
        stored_command = session.get(Command, command_id)
        assert stored_command is not None
        assert stored_command.status == CommandStatus.CONFIRMED.value
        assert (
            session.scalar(
                select(func.count())
                .select_from(CommandItem)
                .where(
                    CommandItem.command_id == command_id,
                    CommandItem.status == CommandItemStatus.CONFIRMED.value,
                )
            )
            == TAG_COUNT
        )

    harness.login()
    start_barrier = Barrier(CLIENT_COUNT)

    def read_snapshots(_client_index: int) -> float:
        start_barrier.wait(timeout=5)
        started = perf_counter()
        command_response = harness.client.get(f"/api/v1/light-commands/{command_id}")
        tags_response = harness.client.get("/api/v1/tags?page=1&page_size=100")
        bindings_response = harness.client.get("/api/v1/bindings?page=1&page_size=100")
        assert command_response.status_code == 200
        assert command_response.json()["target_count"] == TAG_COUNT
        assert len(command_response.json()["items"]) == TAG_COUNT
        assert tags_response.status_code == 200
        assert tags_response.json()["pagination"]["total_items"] == TAG_COUNT
        assert bindings_response.status_code == 200
        assert bindings_response.json()["pagination"]["total_items"] == TAG_COUNT
        return (perf_counter() - started) * 1000

    reads_started = perf_counter()
    with ThreadPoolExecutor(max_workers=CLIENT_COUNT) as executor:
        client_latencies_ms = list(executor.map(read_snapshots, range(CLIENT_COUNT)))
    reads_ms = (perf_counter() - reads_started) * 1000

    assert max(client_latencies_ms) < 15_000
    print(f"capacity_setup_ms={setup_ms:.3f}")
    print(f"capacity_command_ms={command_ms:.3f}")
    print(f"capacity_telemetry_ms={telemetry_ms:.3f}")
    print(
        "capacity_telemetry_sql="
        f"{statements['total']} total/{statements['select']} select"
    )
    print(f"capacity_10_client_wall_ms={reads_ms:.3f}")
    print(f"capacity_10_client_max_ms={max(client_latencies_ms):.3f}")
