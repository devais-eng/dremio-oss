#!/usr/bin/env bash
#
# Seeds test data across all sources for the multi-source UAT.
#
# Data model (e-commerce analytics):
#   PostgreSQL: products (with pgvector embeddings), orders, order_items
#   Oracle:     customers, regions
#   Nessie/Iceberg: product_categories (dimension table)
#
# Cross-source JOINs:
#   PG orders × Oracle customers  (on customer_id)
#   PG products × Iceberg categories (on category_id)
#   pgvector semantic search + JOIN to Oracle customer preferences
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/../docker-compose.yml"

RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'

echo -e "${GREEN}Seeding multi-source test data...${NC}"

# ── PostgreSQL ──────────────────────────────────────────────────────────
echo "Seeding PostgreSQL..."
PG_CONTAINER=$(docker compose -f "$COMPOSE_FILE" ps -q postgres)
docker exec -i "$PG_CONTAINER" psql -U pguser -d testdb -q <<'EOSQL'
CREATE EXTENSION IF NOT EXISTS vector;

-- Products with embeddings (pgvector)
DROP TABLE IF EXISTS order_items CASCADE;
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS products CASCADE;

CREATE TABLE products (
  id INTEGER PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  category_id INTEGER NOT NULL,
  price NUMERIC(10,2) NOT NULL,
  description TEXT,
  embedding vector(4)
);

INSERT INTO products VALUES
  (1, 'Laptop Pro 15', 1, 1299.99, 'High-performance laptop', '[0.8, 0.2, 0.1, 0.9]'),
  (2, 'Wireless Mouse', 1, 29.99, 'Ergonomic wireless mouse', '[0.7, 0.3, 0.2, 0.8]'),
  (3, 'USB-C Hub', 1, 49.99, 'Multi-port USB-C hub', '[0.75, 0.25, 0.15, 0.85]'),
  (4, 'Standing Desk', 2, 599.99, 'Adjustable standing desk', '[0.1, 0.9, 0.8, 0.2]'),
  (5, 'Office Chair', 2, 449.99, 'Ergonomic office chair', '[0.15, 0.85, 0.75, 0.25]'),
  (6, 'Desk Lamp', 2, 79.99, 'LED desk lamp', '[0.2, 0.8, 0.7, 0.3]'),
  (7, 'Mechanical Keyboard', 1, 149.99, 'Cherry MX switches', '[0.72, 0.28, 0.18, 0.82]'),
  (8, 'Monitor 27"', 1, 399.99, '4K IPS display', '[0.78, 0.22, 0.12, 0.88]'),
  (9, 'Webcam HD', 1, 89.99, '1080p webcam', '[0.65, 0.35, 0.25, 0.75]'),
  (10, 'Bookshelf', 2, 199.99, 'Wooden bookshelf', '[0.12, 0.88, 0.78, 0.22]'),
  (11, 'Headphones', 1, 249.99, 'Noise-cancelling', '[0.68, 0.32, 0.22, 0.78]'),
  (12, 'Filing Cabinet', 2, 159.99, 'Metal filing cabinet', '[0.18, 0.82, 0.72, 0.28]'),
  (13, 'Tablet 10"', 1, 499.99, 'Android tablet', '[0.76, 0.24, 0.14, 0.86]'),
  (14, 'Whiteboard', 2, 89.99, 'Magnetic whiteboard', '[0.22, 0.78, 0.68, 0.32]'),
  (15, 'Printer', 1, 299.99, 'Color laser printer', '[0.62, 0.38, 0.28, 0.72]');

CREATE INDEX IF NOT EXISTS products_embedding_l2_idx ON products USING hnsw (embedding vector_l2_ops);

-- Orders
CREATE TABLE orders (
  id INTEGER PRIMARY KEY,
  customer_id INTEGER NOT NULL,
  order_date DATE NOT NULL,
  total NUMERIC(12,2) NOT NULL,
  status VARCHAR(20) NOT NULL
);

INSERT INTO orders VALUES
  (1, 101, '2024-01-15', 1329.98, 'shipped'),
  (2, 102, '2024-01-20', 649.98, 'delivered'),
  (3, 103, '2024-02-01', 499.99, 'shipped'),
  (4, 101, '2024-02-10', 79.99, 'delivered'),
  (5, 104, '2024-02-15', 1749.98, 'processing'),
  (6, 105, '2024-03-01', 449.99, 'delivered'),
  (7, 102, '2024-03-05', 299.99, 'shipped'),
  (8, 103, '2024-03-10', 149.99, 'delivered'),
  (9, 106, '2024-03-15', 89.99, 'shipped'),
  (10, 104, '2024-03-20', 599.99, 'delivered');

-- Order items (links orders to products)
CREATE TABLE order_items (
  id INTEGER PRIMARY KEY,
  order_id INTEGER NOT NULL REFERENCES orders(id),
  product_id INTEGER NOT NULL REFERENCES products(id),
  quantity INTEGER NOT NULL,
  unit_price NUMERIC(10,2) NOT NULL
);

INSERT INTO order_items VALUES
  (1, 1, 1, 1, 1299.99),
  (2, 1, 2, 1, 29.99),
  (3, 2, 4, 1, 599.99),
  (4, 2, 3, 1, 49.99),
  (5, 3, 13, 1, 499.99),
  (6, 4, 6, 1, 79.99),
  (7, 5, 1, 1, 1299.99),
  (8, 5, 5, 1, 449.99),
  (9, 6, 5, 1, 449.99),
  (10, 7, 15, 1, 299.99),
  (11, 8, 7, 1, 149.99),
  (12, 9, 14, 1, 89.99),
  (13, 10, 4, 1, 599.99);
EOSQL
echo -e "${GREEN}PostgreSQL seeded.${NC}"

# ── Oracle ──────────────────────────────────────────────────────────────
echo "Seeding Oracle..."
ORA_CONTAINER=$(docker compose -f "$COMPOSE_FILE" ps -q oracle)
docker exec -i "$ORA_CONTAINER" sqlplus -s testuser/testpass@//localhost:1521/XEPDB1 <<'EOSQL'
BEGIN EXECUTE IMMEDIATE 'DROP TABLE CUSTOMERS'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE REGIONS'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

CREATE TABLE REGIONS (
  ID NUMBER(10) PRIMARY KEY,
  NAME VARCHAR2(100) NOT NULL,
  COUNTRY VARCHAR2(50) NOT NULL
)
/
INSERT ALL
  INTO REGIONS VALUES (1, 'West Coast', 'US')
  INTO REGIONS VALUES (2, 'East Coast', 'US')
  INTO REGIONS VALUES (3, 'Midwest', 'US')
  INTO REGIONS VALUES (4, 'Europe', 'EU')
SELECT 1 FROM DUAL
/

CREATE TABLE CUSTOMERS (
  ID NUMBER(10) PRIMARY KEY,
  NAME VARCHAR2(100) NOT NULL,
  EMAIL VARCHAR2(200) NOT NULL,
  REGION_ID NUMBER(10) NOT NULL,
  TIER VARCHAR2(20) NOT NULL,
  SIGNUP_DATE DATE NOT NULL
)
/
INSERT ALL
  INTO CUSTOMERS VALUES (101, 'Alice Johnson', 'alice@example.com', 1, 'premium', DATE '2023-01-15')
  INTO CUSTOMERS VALUES (102, 'Bob Smith', 'bob@example.com', 2, 'standard', DATE '2023-03-20')
  INTO CUSTOMERS VALUES (103, 'Carol Chen', 'carol@example.com', 1, 'premium', DATE '2023-05-10')
  INTO CUSTOMERS VALUES (104, 'Dave Wilson', 'dave@example.com', 3, 'premium', DATE '2023-07-01')
  INTO CUSTOMERS VALUES (105, 'Eve Brown', 'eve@example.com', 4, 'standard', DATE '2023-09-15')
  INTO CUSTOMERS VALUES (106, 'Frank Lee', 'frank@example.com', 2, 'standard', DATE '2023-11-20')
SELECT 1 FROM DUAL
/
COMMIT
/
EOSQL
echo -e "${GREEN}Oracle seeded.${NC}"

# ── Nessie/Iceberg ─────────────────────────────────────────────────────
echo "Seeding Nessie/Iceberg (via Docker container on same network)..."
docker run --rm --network jdbc-connectors_multi-net \
  python:3.12-slim /bin/sh -c '
pip install --quiet pyiceberg[s3fs]==0.7.1 pyarrow==17.0.0 2>/dev/null
python3 << PYEOF
import pyarrow as pa
from pyiceberg.catalog import load_catalog

catalog = load_catalog("nessie", **{
    "uri": "http://nessie:19120/iceberg/",
    "s3.endpoint": "http://minio:9000",
    "s3.access-key-id": "minioadmin",
    "s3.secret-access-key": "minioadmin",
    "s3.region": "us-east-1",
    "s3.path-style-access": "true",
})

try:
    catalog.create_namespace("analytics")
except Exception:
    pass

schema = pa.schema([
    ("category_id", pa.int32()),
    ("category_name", pa.string()),
    ("department", pa.string()),
    ("margin_pct", pa.float64()),
])
data = pa.table({
    "category_id": [1, 2, 3],
    "category_name": ["Electronics", "Furniture", "Accessories"],
    "department": ["Tech", "Home", "Tech"],
    "margin_pct": [0.15, 0.25, 0.30],
}, schema=schema)
try:
    catalog.drop_table("analytics.product_categories")
except Exception:
    pass
tbl = catalog.create_table("analytics.product_categories", schema=schema)
tbl.append(data)
print(f"Created analytics.product_categories: {len(data)} rows")

sales_schema = pa.schema([
    ("month", pa.string()),
    ("product_id", pa.int32()),
    ("units_sold", pa.int32()),
    ("revenue", pa.float64()),
])
sales_data = pa.table({
    "month": ["2024-01", "2024-01", "2024-02", "2024-02", "2024-03", "2024-03",
              "2024-01", "2024-02", "2024-03", "2024-01", "2024-02", "2024-03"],
    "product_id": [1, 4, 1, 13, 5, 7, 2, 6, 15, 8, 3, 14],
    "units_sold": [5, 3, 8, 2, 4, 6, 15, 7, 3, 4, 10, 5],
    "revenue": [6499.95, 1799.97, 10399.92, 999.98, 1799.96, 899.94,
                449.85, 559.93, 899.97, 1599.96, 499.90, 449.95],
}, schema=sales_schema)
try:
    catalog.drop_table("analytics.monthly_sales")
except Exception:
    pass
tbl2 = catalog.create_table("analytics.monthly_sales", schema=sales_schema)
tbl2.append(sales_data)
print(f"Created analytics.monthly_sales: {len(sales_data)} rows")
PYEOF
'
echo -e "${GREEN}Nessie/Iceberg seeded.${NC}"

# ── MinIO S3 Parquet files ──────────────────────────────────────────────
echo "Seeding MinIO S3 Parquet data (via Docker container)..."
docker run --rm --network jdbc-connectors_multi-net \
  python:3.12-slim /bin/sh -c '
pip install --quiet pyarrow==17.0.0 boto3 2>/dev/null
python3 << PYEOF
import pyarrow as pa
import pyarrow.parquet as pq
import boto3, io

s3 = boto3.client("s3",
    endpoint_url="http://minio:9000",
    aws_access_key_id="minioadmin",
    aws_secret_access_key="minioadmin",
    region_name="us-east-1")

# Shipping rates dimension table
schema = pa.schema([
    ("region_id", pa.int32()),
    ("region_name", pa.string()),
    ("shipping_rate", pa.float64()),
    ("delivery_days", pa.int32()),
])
data = pa.table({
    "region_id": [1, 2, 3, 4],
    "region_name": ["West Coast", "East Coast", "Midwest", "Europe"],
    "shipping_rate": [5.99, 7.99, 6.99, 15.99],
    "delivery_days": [3, 4, 5, 10],
}, schema=schema)

buf = io.BytesIO()
pq.write_table(data, buf)
buf.seek(0)
s3.put_object(Bucket="parquet-data", Key="shipping_rates/data.parquet", Body=buf.getvalue())
print(f"Uploaded shipping_rates: {len(data)} rows")

# Product reviews fact table
reviews_schema = pa.schema([
    ("review_id", pa.int32()),
    ("product_id", pa.int32()),
    ("rating", pa.int32()),
    ("review_text", pa.string()),
])
reviews = pa.table({
    "review_id": [1, 2, 3, 4, 5, 6, 7, 8],
    "product_id": [1, 1, 4, 5, 7, 8, 13, 2],
    "rating": [5, 4, 5, 3, 4, 5, 4, 5],
    "review_text": ["Great laptop", "Good value", "Solid desk", "OK chair",
                    "Love the keys", "Crisp display", "Nice tablet", "Perfect mouse"],
}, schema=reviews_schema)

buf2 = io.BytesIO()
pq.write_table(reviews, buf2)
buf2.seek(0)
s3.put_object(Bucket="parquet-data", Key="product_reviews/data.parquet", Body=buf2.getvalue())
print(f"Uploaded product_reviews: {len(reviews)} rows")
PYEOF
'
echo -e "${GREEN}MinIO S3 Parquet seeded.${NC}"

echo ""
echo -e "${GREEN}All sources seeded successfully.${NC}"
echo "Sources to create in Dremio:"
echo "  pg_jdbc       → POSTGRES_DB"
echo "  pg_adbc       → POSTGRES_DB (AUTO mode)"
echo "  oracle_src    → ORACLE_DB"
echo "  nessie_rest   → RESTCATALOG (Iceberg REST)"
echo "  nessie_ver    → NESSIE (versioned catalog)"
echo "  s3_parquet    → S3 (MinIO raw Parquet)"
