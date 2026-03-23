"""Seed sample data into Polaris via the Iceberg REST Catalog API."""

import os
import sys
import time

import pyarrow as pa
from pyiceberg.catalog.rest import RestCatalog


def wait_for_polaris(max_retries: int = 30):
    """Wait until Polaris health endpoint is reachable."""
    import urllib.request

    health_url = "http://polaris:8182/q/health"
    for i in range(max_retries):
        try:
            urllib.request.urlopen(health_url, timeout=3)
            return
        except Exception:
            print(f"  Waiting for Polaris... ({i + 1}/{max_retries})")
            time.sleep(2)
    print("ERROR: Polaris did not become ready", file=sys.stderr)
    sys.exit(1)


def main():
    catalog_uri = os.environ.get("CATALOG_URI", "http://polaris:8181/api/catalog")
    client_id = os.environ.get("POLARIS_CLIENT_ID", "root")
    client_secret = os.environ.get("POLARIS_CLIENT_SECRET", "s3cr3t")
    realm = os.environ.get("POLARIS_REALM", "POLARIS")
    polaris_catalog = os.environ.get("POLARIS_CATALOG", "polaris_catalog")
    s3_endpoint = os.environ.get("S3_ENDPOINT", "http://minio:9000")
    s3_access_key = os.environ.get("S3_ACCESS_KEY", "minioadmin")
    s3_secret_key = os.environ.get("S3_SECRET_KEY", "minioadmin")

    print("=== Seeding sample data into Polaris ===")
    wait_for_polaris()

    catalog_props = {
        "uri": catalog_uri,
        "credential": f"{client_id}:{client_secret}",
        "scope": "PRINCIPAL_ROLE:ALL",
        "warehouse": polaris_catalog,
        "header.Polaris-Realm": realm,
        "s3.endpoint": s3_endpoint,
        "s3.access-key-id": s3_access_key,
        "s3.secret-access-key": s3_secret_key,
        "s3.path-style-access": "true",
        "s3.region": "us-east-1",
    }

    catalog = RestCatalog("polaris", **catalog_props)

    # ── Namespace: logistics ──────────────────────────────────────────
    ns = ("logistics",)
    try:
        catalog.create_namespace(ns, {"description": "Logistics namespace"})
        print(f"  Created namespace: {ns[0]}")
    except Exception as e:
        if "already exists" in str(e).lower() or "AlreadyExists" in type(e).__name__:
            print(f"  Namespace '{ns[0]}' already exists, skipping.")
        else:
            raise

    # ── Table: logistics.shipments ────────────────────────────────────
    shipments_id = "logistics.shipments"
    shipments_data = pa.table(
        {
            "shipment_id": pa.array([1001, 1002, 1003, 1004, 1005], type=pa.int64()),
            "origin": pa.array(
                ["New York", "London", "Tokyo", "Berlin", "Paris"],
                type=pa.string(),
            ),
            "destination": pa.array(
                ["London", "Tokyo", "Berlin", "Paris", "New York"],
                type=pa.string(),
            ),
            "weight_kg": pa.array(
                [250.0, 180.5, 320.0, 95.0, 410.0], type=pa.float64()
            ),
            "status": pa.array(
                ["delivered", "in_transit", "delivered", "pending", "in_transit"],
                type=pa.string(),
            ),
        }
    )

    try:
        tbl = catalog.create_table(shipments_id, schema=shipments_data.schema)
        tbl.append(shipments_data)
        print(f"  Created table: {shipments_id} ({len(shipments_data)} rows)")
    except Exception as e:
        if "already exists" in str(e).lower() or "AlreadyExists" in type(e).__name__:
            print(f"  Table '{shipments_id}' already exists, skipping.")
        else:
            raise

    # ── Table: logistics.carriers ─────────────────────────────────────
    carriers_id = "logistics.carriers"
    carriers_data = pa.table(
        {
            "carrier_id": pa.array([1, 2, 3, 4], type=pa.int64()),
            "name": pa.array(
                ["FastFreight", "OceanCargo", "AirExpress", "RailLink"],
                type=pa.string(),
            ),
            "mode": pa.array(
                ["truck", "ship", "air", "rail"], type=pa.string()
            ),
            "rate_per_kg": pa.array(
                [2.50, 1.20, 5.80, 1.80], type=pa.float64()
            ),
        }
    )

    try:
        tbl = catalog.create_table(carriers_id, schema=carriers_data.schema)
        tbl.append(carriers_data)
        print(f"  Created table: {carriers_id} ({len(carriers_data)} rows)")
    except Exception as e:
        if "already exists" in str(e).lower() or "AlreadyExists" in type(e).__name__:
            print(f"  Table '{carriers_id}' already exists, skipping.")
        else:
            raise

    # ── Summary ───────────────────────────────────────────────────────
    print("\n=== Seed complete ===")
    print("  Namespace: logistics")
    print("  Tables:    logistics.shipments (5 rows), logistics.carriers (4 rows)")
    print("\n  Try in Dremio SQL:")
    print("    SELECT * FROM polaris_catalog.logistics.shipments")
    print("    SELECT * FROM polaris_catalog.logistics.carriers")


if __name__ == "__main__":
    main()
