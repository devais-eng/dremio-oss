"""Seed sample data into Nessie via the Iceberg REST Catalog API."""

import os
import sys
import time

import pyarrow as pa
from pyiceberg.catalog.rest import RestCatalog


def wait_for_nessie(max_retries: int = 30):
    """Wait until Nessie management endpoint is reachable (no auth required)."""
    import urllib.request

    health_url = os.environ.get(
        "NESSIE_HEALTH_URL", "http://nessie:9000/q/health/ready"
    )
    for i in range(max_retries):
        try:
            urllib.request.urlopen(health_url, timeout=3)
            return
        except Exception:
            print(f"  Waiting for Nessie... ({i + 1}/{max_retries})")
            time.sleep(2)
    print("ERROR: Nessie did not become ready", file=sys.stderr)
    sys.exit(1)


def main():
    nessie_uri = os.environ.get("NESSIE_URI", "http://nessie:19120/iceberg/")
    oauth2_server = os.environ.get("OAUTH2_SERVER_URI", "")
    credential = os.environ.get("CREDENTIAL", "")
    warehouse = os.environ.get("WAREHOUSE", "warehouse")

    print("=== Seeding sample data into Nessie ===")
    wait_for_nessie()

    s3_endpoint = os.environ.get("S3_ENDPOINT", "http://minio:9000")
    s3_access_key = os.environ.get("S3_ACCESS_KEY", "minioadmin")
    s3_secret_key = os.environ.get("S3_SECRET_KEY", "minioadmin")

    catalog_props = {
        "uri": nessie_uri,
        "warehouse": warehouse,
        "s3.endpoint": s3_endpoint,
        "s3.access-key-id": s3_access_key,
        "s3.secret-access-key": s3_secret_key,
        "s3.path-style-access": "true",
        "s3.region": "us-east-1",
    }

    # OAuth2 is optional — only used when Keycloak is present (SSO mode)
    if oauth2_server and credential:
        print("  OAuth2 enabled (Keycloak)")
        catalog_props["oauth2-server-uri"] = oauth2_server
        catalog_props["credential"] = credential
        catalog_props["scope"] = "catalog sign"
    else:
        print("  No OAuth2 (Nessie auth disabled)")

    catalog = RestCatalog("nessie", **catalog_props)

    # ── Namespace: demo ─────────────────────────────────────────────────
    ns = ("demo",)
    try:
        catalog.create_namespace(ns, {"description": "Demo namespace"})
        print(f"  Created namespace: {ns[0]}")
    except Exception as e:
        if "already exists" in str(e).lower() or "AlreadyExists" in type(e).__name__:
            print(f"  Namespace '{ns[0]}' already exists, skipping.")
        else:
            raise

    # ── Table: demo.customers ───────────────────────────────────────────
    customers_id = "demo.customers"
    customers_data = pa.table(
        {
            "id": pa.array([1, 2, 3, 4, 5], type=pa.int64()),
            "name": pa.array(
                ["Alice", "Bob", "Charlie", "Diana", "Eve"], type=pa.string()
            ),
            "email": pa.array(
                [
                    "alice@example.com",
                    "bob@example.com",
                    "charlie@example.com",
                    "diana@example.com",
                    "eve@example.com",
                ],
                type=pa.string(),
            ),
            "city": pa.array(
                ["New York", "London", "Tokyo", "Paris", "Berlin"], type=pa.string()
            ),
        }
    )

    try:
        tbl = catalog.create_table(customers_id, schema=customers_data.schema)
        tbl.append(customers_data)
        print(f"  Created table: {customers_id} ({len(customers_data)} rows)")
    except Exception as e:
        if "already exists" in str(e).lower() or "AlreadyExists" in type(e).__name__:
            print(f"  Table '{customers_id}' already exists, skipping.")
        else:
            raise

    # ── Table: demo.orders ──────────────────────────────────────────────
    orders_id = "demo.orders"
    orders_data = pa.table(
        {
            "order_id": pa.array([101, 102, 103, 104, 105, 106], type=pa.int64()),
            "customer_id": pa.array([1, 2, 1, 3, 4, 5], type=pa.int64()),
            "product": pa.array(
                ["Laptop", "Phone", "Tablet", "Monitor", "Keyboard", "Mouse"],
                type=pa.string(),
            ),
            "amount": pa.array(
                [1299.99, 899.50, 549.00, 459.99, 129.99, 49.99], type=pa.float64()
            ),
        }
    )

    try:
        tbl = catalog.create_table(orders_id, schema=orders_data.schema)
        tbl.append(orders_data)
        print(f"  Created table: {orders_id} ({len(orders_data)} rows)")
    except Exception as e:
        if "already exists" in str(e).lower() or "AlreadyExists" in type(e).__name__:
            print(f"  Table '{orders_id}' already exists, skipping.")
        else:
            raise

    # ── Summary ─────────────────────────────────────────────────────────
    print("\n=== Seed complete ===")
    print("  Namespace: demo")
    print("  Tables:    demo.customers (5 rows), demo.orders (6 rows)")
    print("\n  Try in Dremio SQL:")
    print('    SELECT * FROM nessie_catalog.demo.customers')
    print('    SELECT c.name, o.product, o.amount')
    print('      FROM nessie_catalog.demo.orders o')
    print('      JOIN nessie_catalog.demo.customers c ON o.customer_id = c.id')


if __name__ == "__main__":
    main()
