from io import BytesIO

from openpyxl import Workbook


XLSX_MEDIA_TYPE = (
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
)


def _workbook(rows: list[list[str]]) -> bytes:
    workbook = Workbook()
    sheet = workbook.active
    for row in rows:
        sheet.append(row)
    stream = BytesIO()
    workbook.save(stream)
    return stream.getvalue()


def test_excel_preview_get_commit_and_backup(harness) -> None:
    auth = harness.login()
    content = _workbook(
        [
            ["Product Code", "Product Name"],
            [" p-1 ", "First"],
            ["P-2", "Second"],
        ]
    )
    preview = harness.client.post(
        "/api/v1/products/imports",
        headers=harness.mutation_headers(auth),
        files={"file": ("products.xlsx", content, XLSX_MEDIA_TYPE)},
    )
    assert preview.status_code == 202, preview.text
    assert preview.json()["status"] == "READY"
    assert preview.json()["error_rows"] == 0
    assert preview.json()["total_rows"] == 2
    job_id = preview.json()["id"]

    detail = harness.client.get(f"/api/v1/products/imports/{job_id}")
    assert detail.status_code == 200
    assert detail.json() == preview.json()
    no_errors = harness.client.get(
        f"/api/v1/products/imports/{job_id}/errors"
    )
    assert no_errors.status_code == 409

    committed = harness.client.post(
        f"/api/v1/products/imports/{job_id}/commit",
        headers=harness.mutation_headers(auth),
    )
    assert committed.status_code == 202
    assert committed.json()["status"] == "COMMITTED"
    products = harness.client.get("/api/v1/products")
    assert products.status_code == 200
    assert {
        item["product_code"] for item in products.json()["items"]
    } == {"P-1", "P-2"}
    assert "pagination" in products.json()

    backup = harness.client.post(
        "/api/v1/backups", headers=harness.mutation_headers(auth)
    )
    assert backup.status_code == 202, backup.text
    assert backup.json()["status"] == "SUCCEEDED"
    assert len(backup.json()["sha256"]) == 64
    assert (
        harness.settings.backups_dir / backup.json()["filename"]
    ).is_file()


def test_invalid_import_exposes_csv_errors(harness) -> None:
    auth = harness.login()
    content = _workbook(
        [
            ["Product Code", "Product Name"],
            ["P-1", "First"],
            ["P-1", "Duplicate"],
        ]
    )
    preview = harness.client.post(
        "/api/v1/products/imports",
        headers=harness.mutation_headers(auth),
        files={"file": ("products.xlsx", content, XLSX_MEDIA_TYPE)},
    )
    assert preview.status_code == 202
    assert preview.json()["status"] == "INVALID"
    assert preview.json()["error_rows"] == 1
    errors = harness.client.get(
        f"/api/v1/products/imports/{preview.json()['id']}/errors"
    )
    assert errors.status_code == 200
    assert errors.headers["content-type"].startswith("text/csv")
    assert "DUPLICATE_PRODUCT_CODE" in errors.text
