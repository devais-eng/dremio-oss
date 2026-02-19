# RBAC Features: Table Stakes vs. Differentiators vs. Anti-Features

**Research Date:** 2026-02-17
**Research Type:** Features dimension — what database RBAC systems have, scoped to views and UDFs.
**Milestone:** Subsequent — adding RBAC to an existing data lakehouse platform.

---

## Framing: What "Naive RBAC on Views and UDFs" Means

The scope here is deliberate and narrow: protect virtual datasets (views) and user-defined functions using role-based access control, with a definer-rights model for view expansion. The security boundary is the outermost entity the user referenced, not the underlying physical tables. This is the standard SQL pattern used by PostgreSQL, MySQL, Oracle, and most ANSI SQL-compliant engines.

The question for each candidate feature: does it need to exist for the system to be useful, or is it a refinement that delivers incremental value on top of a working baseline?

---

## Category 1: Table Stakes

*Must have for any RBAC to be minimally useful. Without these, the system either cannot be administered, cannot be enforced, or is obviously broken from a user perspective.*

### 1.1 Role Lifecycle Management

**What it is:** CREATE ROLE, DROP ROLE — the ability to define named roles that can receive privileges.

**Why it's table stakes:** Without named roles, you cannot assign privileges to anything. Roles are the grouping primitive that makes RBAC different from per-user ACLs.

**Complexity:** Low. A role is a name, an ID, and metadata. No computation at check time.

**Dependencies:** None (foundational).

**What Dremio already has:** SQL DDL parser (`SqlCreateRole`, `SqlDropRole`) exists but dispatches to an `UnsupportedError` in OSS. The system table schema (`SysTableRoleInfo`) exists with fields: `role_id`, `role_name`, `role_type`, `owner_id`, `owner_type`, `created_by`. Persistence layer needs to be wired up.

### 1.2 Role Membership: Assigning Users to Roles

**What it is:** GRANT ROLE TO USER, REVOKE ROLE FROM USER — the ability to put users into roles.

**Why it's table stakes:** Without membership, roles exist but confer nothing.

**Complexity:** Low. A membership is a (role_id, user_id) pair.

**Dependencies:** Role Lifecycle Management (1.1).

**What Dremio already has:** `SqlGrantRole` and `SqlRevokeRole` parsers exist. `SysTableMembershipInfo` schema has `role_name`, `member_name`, `member_type`.

### 1.3 Privilege Grants on Objects: SELECT on VDS, EXECUTE on UDFs

**What it is:** GRANT SELECT ON VDS TO ROLE, GRANT EXECUTE ON FUNCTION TO ROLE, and their REVOKE counterparts.

**Why it's table stakes:** This is the core of the system. Without privilege grants, there is nothing to check at enforcement time.

**Complexity:** Low-to-medium. Storing a (privilege, object_type, object_path, role_id) tuple.

**Dependencies:** Role Lifecycle Management (1.1), Role Membership (1.2).

**What Dremio already has:** `SqlGrant.Privilege` enum has SELECT, EXECUTE, CREATE_VIEW, and many more. `SqlGrant.GrantType` has VDS, FUNCTION, etc. `SysTablePrivilegeInfo` schema exists.

### 1.4 Deny-by-Default Policy

**What it is:** No privilege grant means no access. Access is denied unless there is an explicit allow.

**Why it's table stakes:** A system that allows by default is not an access control system.

**Complexity:** Low. It is the absence of a special check, not additional logic.

**Dependencies:** Privilege enforcement (1.3 and 1.5).

### 1.5 Catalog-Level Privilege Enforcement

**What it is:** The actual runtime check — when a user tries to resolve a view or execute a UDF, verify they have the required privilege.

**Complexity:** Medium. The check itself is a lookup. The work is wiring it into all access paths without breaking bypass paths.

**Dependencies:** Role Membership (1.2), Privilege Grants (1.3), Deny-by-default (1.4).

**What Dremio already has:** `CatalogImpl.validatePrivilege(NamespaceKey, SqlGrant.Privilege)` exists as a no-op.

### 1.6 Built-in ADMIN Role (Bypass All Checks)

**What it is:** A role that bypasses all RBAC checks.

**Why it's table stakes:** Without a superuser bypass, the administrator cannot manage the system (bootstrapping problem).

**Complexity:** Low. At enforcement time: if user has ADMIN role, return immediately.

### 1.7 Built-in PUBLIC Role (All Users Implicitly)

**What it is:** A role that every user implicitly belongs to. Grants to PUBLIC apply to all users.

**Complexity:** Low. At membership resolution: always include PUBLIC in every user's role set.

### 1.8 Observability: System Tables (sys.roles, sys.privileges, sys.membership)

**What it is:** Queryable system tables that expose the current state of roles, grants, and memberships.

**Why it's table stakes:** Without visibility, administrators cannot audit or debug the access control system.

**Complexity:** Low (read-only projection over the KV store). Schema already defined.

**What Dremio already has:** `AccessControlListingManager` interface, all three `SysTable*Info` classes. OSS implementation returns empty iterables.

### 1.9 SQL DDL Interface: GRANT / REVOKE / CREATE ROLE / DROP ROLE

**What it is:** Standard SQL syntax for administering RBAC.

**Complexity:** Low — parsers already exist. The work is wiring parsed AST to real handler implementations.

**What Dremio already has:** Full SQL DDL parsers exist. All dispatch to handlers that throw `UnsupportedError` in OSS.

### 1.10 Privilege Grant for CREATE OR REPLACE on VDS

**What it is:** A privilege that controls who can create or overwrite virtual datasets (views).

**Complexity:** Low-to-medium. Similar enforcement path to SELECT, but triggered on DDL operations.

**What Dremio already has:** `SqlGrant.Privilege.CREATE_VIEW` and `CREATE_FUNCTION` exist in the enum.

---

## Category 2: Differentiators

*Nice to have, but not essential for a functional "naive v1".*

### 2.1 REST API for Role and Grant Management

**Complexity:** Medium. Standard Jersey JAX-RS resource classes.

### 2.2 GRANT OPTION (WITH GRANT OPTION)

**Complexity:** Medium. Requires tracking grantor, cascade on revocation.

### 2.3 SHOW GRANTS / SHOW ROLES SQL Commands

**Complexity:** Low. Syntactic sugar over system tables.

### 2.4 Privilege Check Caching

**Complexity:** Medium. Must invalidate on GRANT/REVOKE. Cross-coordinator invalidation is non-trivial.

### 2.5 Privilege Inheritance via Container Grants (Schema/Space-Level)

**Complexity:** High. Requires namespace path traversal at check time.

### 2.6 Audit Logging for RBAC DDL Operations

**Complexity:** Low-to-medium. Capture actor, action, target, timestamp.

### 2.7 REVOKE Cascade Semantics

**Complexity:** High. Depends on WITH GRANT OPTION (2.2).

### 2.8 Object Ownership Model (TRANSFER OWNERSHIP)

**Complexity:** Medium. Depends on namespace metadata.

---

## Category 3: Anti-Features

*Things to deliberately NOT build in v1.*

### 3.1 Nested Roles (Role Hierarchy)
Privilege resolution becomes recursive. Flat roles are sufficient for v1.

### 3.2 DENY Grants (Negative Permissions)
Deny-by-default already achieves the core goal. DENY adds confusing priority resolution.

### 3.3 Row-Level Security (RLS)
Views already serve as the row-filtering mechanism.

### 3.4 Column-Level Security (CLS)
Views are the column projection mechanism.

### 3.5 Source-Level or Space-Level Permissions
Requires namespace path traversal. Per-object grants are sufficient for v1.

### 3.6 Physical Dataset (PDS) Permissions
Definer-rights model makes PDS permissions redundant for the stated security model.

### 3.7 Planner-Level Enforcement
Catalog-level enforcement covers all access paths. Planner-level would be redundant.

---

## Feature Dependency Map

```
1.1 Role Lifecycle
  └─> 1.2 Role Membership
        └─> 1.3 Privilege Grants (SELECT on VDS, EXECUTE on UDF, CREATE_VIEW on VDS)
              └─> 1.4 Deny-by-Default (policy, not a feature)
              └─> 1.5 Catalog Enforcement (runtime check)
                    └─> 1.6 ADMIN bypass (short-circuit in enforcement)
                    └─> 1.7 PUBLIC role (implicit membership in enforcement)
              └─> 1.8 System Tables (observability)
              └─> 1.9 SQL DDL (admin interface)
              └─> 1.10 CREATE OR REPLACE privilege (write-side enforcement)

Differentiators (all depend on 1.1–1.10 being complete):
  2.1 REST API        -- UI integration
  2.2 WITH GRANT OPTION -- delegation
  2.3 SHOW GRANTS     -- ergonomics
  2.4 Privilege cache -- performance
  2.5 Container grants -- scalability
  2.6 Audit logging   -- compliance
  2.7 Revoke cascade  -- depends on 2.2
  2.8 Ownership model -- depends on namespace metadata
```

---

## Complexity Summary Table

| Feature | Category | Complexity | Key Dependency |
|---------|----------|-----------|----------------|
| 1.1 Role lifecycle | Table stakes | Low | None |
| 1.2 Role membership | Table stakes | Low | 1.1 |
| 1.3 Privilege grants | Table stakes | Low-medium | 1.1, 1.2 |
| 1.4 Deny-by-default | Table stakes | Low | 1.3, 1.5 |
| 1.5 Catalog enforcement | Table stakes | Medium | 1.2, 1.3 |
| 1.6 ADMIN role bypass | Table stakes | Low | 1.1 |
| 1.7 PUBLIC role | Table stakes | Low | 1.2 |
| 1.8 System tables | Table stakes | Low | 1.1–1.3 |
| 1.9 SQL DDL | Table stakes | Low | 1.1–1.3 |
| 1.10 CREATE OR REPLACE privilege | Table stakes | Low-medium | 1.3, 1.5 |
| 2.1 REST API | Differentiator | Medium | All table stakes |
| 2.4 Privilege caching | Differentiator | Medium | 1.5 |
| 2.6 Audit logging | Differentiator | Low-medium | 1.1–1.3 |

---

*Research: 2026-02-17. Synthesized from SQL standard (SQL:1999, SQL:2003), PostgreSQL 16, Snowflake RBAC, Databricks Unity Catalog, BigQuery IAM, and Dremio OSS codebase analysis.*
