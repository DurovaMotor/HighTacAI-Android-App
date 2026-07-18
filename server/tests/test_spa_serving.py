from fastapi.testclient import TestClient

from hightac_platform.config import Settings
from hightac_platform.main import create_app

from conftest import FakePublisher


def test_optional_same_origin_spa_serving_keeps_api_routes(
    tmp_path,
) -> None:
    web_dist = tmp_path / "web-dist"
    assets = web_dist / "assets"
    assets.mkdir(parents=True)
    (web_dist / "index.html").write_text(
        "<html><body>HighTac SPA</body></html>", encoding="utf-8"
    )
    (assets / "app.js").write_text(
        "window.HIGHTAC = true;", encoding="utf-8"
    )
    settings = Settings(
        environment="test",
        data_dir=tmp_path / "data",
        database_url=(
            f"sqlite:///{(tmp_path / 'spa-test.db').as_posix()}"
        ),
        bootstrap_admin=False,
        mqtt_enabled=False,
        command_scheduler_enabled=False,
        log_to_file=False,
        serve_web_spa=True,
        web_dist_dir=web_dist,
    )
    app = create_app(settings, publisher=FakePublisher())
    with TestClient(app) as client:
        deep_link = client.get("/products/example")
        assert deep_link.status_code == 200
        assert "HighTac SPA" in deep_link.text
        asset = client.get("/assets/app.js")
        assert asset.status_code == 200
        assert "window.HIGHTAC" in asset.text
        live = client.get("/api/v1/health/live")
        assert live.status_code == 200
        assert live.json()["service"] == "HighTacPlatform"
