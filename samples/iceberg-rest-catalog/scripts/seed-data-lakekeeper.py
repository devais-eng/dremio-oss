"""Seed sample data into Lakekeeper via the Iceberg REST Catalog API."""

import os
import sys
import time

import pyarrow as pa
from pyiceberg.catalog.rest import RestCatalog


def wait_for_lakekeeper(max_retries: int = 30):
    """Wait until Lakekeeper catalog endpoint is reachable."""
    import urllib.request

    catalog_uri = os.environ.get("CATALOG_URI", "http://lakekeeper:8181/catalog")
    health_url = f"{catalog_uri}/v1/config"
    for i in range(max_retries):
        try:
            urllib.request.urlopen(health_url, timeout=3)
            return
        except Exception:
            print(f"  Waiting for Lakekeeper... ({i + 1}/{max_retries})")
            time.sleep(2)
    print("ERROR: Lakekeeper did not become ready", file=sys.stderr)
    sys.exit(1)


def main():
    catalog_uri = os.environ.get("CATALOG_URI", "http://lakekeeper:8181/catalog")
    warehouse = os.environ.get("WAREHOUSE", "lakehouse")
    s3_endpoint = os.environ.get("S3_ENDPOINT", "http://minio:9000")
    s3_access_key = os.environ.get("S3_ACCESS_KEY", "minioadmin")
    s3_secret_key = os.environ.get("S3_SECRET_KEY", "minioadmin")

    print("=== Seeding sample data into Lakekeeper ===")
    wait_for_lakekeeper()

    catalog_props = {
        "uri": catalog_uri,
        "warehouse": warehouse,
        "s3.endpoint": s3_endpoint,
        "s3.access-key-id": s3_access_key,
        "s3.secret-access-key": s3_secret_key,
        "s3.path-style-access": "true",
        "s3.region": "us-east-1",
    }

    catalog = RestCatalog("lakekeeper", **catalog_props)

    # ── Namespace: inventory ──────────────────────────────────────────
    ns = ("inventory",)
    try:
        catalog.create_namespace(ns, {"description": "Inventory namespace"})
        print(f"  Created namespace: {ns[0]}")
    except Exception as e:
        if "already exists" in str(e).lower() or "AlreadyExists" in type(e).__name__:
            print(f"  Namespace '{ns[0]}' already exists, skipping.")
        else:
            raise

    # ── Table: inventory.products ─────────────────────────────────────
    products_id = "inventory.products"
    products_data = pa.table(
        {
            "product_id": pa.array([1, 2, 3, 4, 5, 6], type=pa.int64()),
            "name": pa.array(
                ["Laptop", "Phone", "Tablet", "Monitor", "Keyboard", "Mouse"],
                type=pa.string(),
            ),
            "category": pa.array(
                [
                    "Electronics",
                    "Electronics",
                    "Electronics",
                    "Electronics",
                    "Accessories",
                    "Accessories",
                ],
                type=pa.string(),
            ),
            "price": pa.array(
                [1299.99, 899.50, 549.00, 459.99, 129.99, 49.99], type=pa.float64()
            ),
            "stock": pa.array([50, 120, 80, 35, 200, 300], type=pa.int64()),
        }
    )

    try:
        tbl = catalog.create_table(products_id, schema=products_data.schema)
        tbl.append(products_data)
        print(f"  Created table: {products_id} ({len(products_data)} rows)")
    except Exception as e:
        if "already exists" in str(e).lower() or "AlreadyExists" in type(e).__name__:
            print(f"  Table '{products_id}' already exists, skipping.")
        else:
            raise

    # ── Table: inventory.warehouses ───────────────────────────────────
    warehouses_id = "inventory.warehouses"
    warehouses_data = pa.table(
        {
            "warehouse_id": pa.array([1, 2, 3], type=pa.int64()),
            "location": pa.array(
                ["New York", "London", "Tokyo"], type=pa.string()
            ),
            "capacity": pa.array([10000, 8000, 12000], type=pa.int64()),
        }
    )

    try:
        tbl = catalog.create_table(warehouses_id, schema=warehouses_data.schema)
        tbl.append(warehouses_data)
        print(f"  Created table: {warehouses_id} ({len(warehouses_data)} rows)")
    except Exception as e:
        if "already exists" in str(e).lower() or "AlreadyExists" in type(e).__name__:
            print(f"  Table '{warehouses_id}' already exists, skipping.")
        else:
            raise

    # ── Summary ───────────────────────────────────────────────────────
    print("\n=== Seed complete ===")
    print("  Namespace: inventory")
    print("  Tables:    inventory.products (6 rows), inventory.warehouses (3 rows)")
    print("\n  Try in Dremio SQL:")
    print("    SELECT * FROM lakekeeper_catalog.inventory.products")
    print("    SELECT * FROM lakekeeper_catalog.inventory.warehouses")


if __name__ == "__main__":
    main()
