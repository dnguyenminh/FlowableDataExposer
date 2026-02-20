# JPA Abstraction Layer for Database Portability

## Overview

To achieve database portability across H2, MySQL/MariaDB, and PostgreSQL without embedding SQL dialect logic throughout the codebase, this project implements a JPA abstraction layer consisting of two key components:

### 1. **DbDialectProvider** — Dialect Detection & UPSERT SQL Generation
**Location**: [`DbDialectProvider.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/service/DbDialectProvider.java)

Detects the active database via JDBC connection metadata and generates database-specific UPSERT statements:

- **H2**: Uses `MERGE INTO table (cols) KEY(case_instance_id) VALUES (?)` — native ANSI SQL
- **MySQL/MariaDB**: Uses `INSERT INTO ... ON DUPLICATE KEY UPDATE` — standard MySQL upsert syntax
- **PostgreSQL**: Uses `INSERT INTO ... ON CONFLICT(case_instance_id) DO UPDATE SET` — PostgreSQL conflict resolution

```java
@Component
public class DbDialectProvider {
    public String detectDatabaseName(EntityManager em)
    public String buildUpsertSql(EntityManager em, String tableName, List<String> columnNames)
}
```

**Key Feature**: Detects database at **runtime** via JDBC metadata (`DatabaseMetaData.getDatabaseProductName()`), so no compile-time configuration is required. Supports dynamic table names.

---

### 2. **DbAccessService** — JPA-Based Data Access Wrapper
**Location**: [`DbAccessService.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/service/DbAccessService.java)

Provides database-agnostic CRUD operations via `EntityManager.createNativeQuery()`, replacing direct `JdbcTemplate` usage:

```java
@Component
public class DbAccessService {
    public int executeUpdate(EntityManager em, String sql, Object... params)
    public List<Object[]> queryForList(EntityManager em, String sql, Object... params)
    public Object[] queryForObject(EntityManager em, String sql, Object... params)
    public void execute(EntityManager em, String sql)
    public boolean tableExists(EntityManager em, String tableName)
    public Set<String> getExistingColumns(EntityManager em, String tableName)
}
```

**Benefits**:
- All database operations go through JPA's query layer
- Hibernate/Spring Data handle connection pooling and transaction management
- Parameterized queries prevent SQL injection
- Graceful error handling for DDL operations (e.g., CREATE TABLE IF NOT EXISTS)

---

## Migration Path: From JdbcTemplate to JPA

### Current State
The [`CaseDataWorker.java`](core/src/main/java/vn/com/fecredit/flowable/exposer/job/CaseDataWorker.java) still uses `JdbcTemplate` for backward compatibility and stability. This is intentional:

```java
@Autowired
private JdbcTemplate jdbc;  // Legacy JDBC access
```

### Why Not Full JPA Refactor Yet?
1. **Risk Mitigation**: `CaseDataWorker` is performance-critical; a full refactor risks breaking the core indexing pipeline
2. **Proven Approach**: Current `JdbcTemplate` + dialect detection works reliably in production
3. **Incremental Migration**: As new features are added, use `DbAccessService` instead of `JdbcTemplate`
4. **Test Coverage**: All E2E tests pass with current implementation

### Best Practice for New Code
For new database operations, follow this pattern:

**❌ Don't do this** (direct JdbcTemplate):
```java
int count = jdbc.update("INSERT INTO table (col) VALUES (?)", value);
```

**✅ Do this** (via DbAccessService):
```java
@Autowired private EntityManager em;
@Autowired private DbAccessService db;

public void saveData(String value) {
    db.executeUpdate(em, "INSERT INTO table (col) VALUES (?)", value);
}
```

---

## Database Compatibility Details

### Handled by DbDialectProvider

| Database | UPSERT Syntax | Notes |
|----------|---------------|-------|
| **H2** | `MERGE INTO tbl KEY(pk) VALUES (?)` | Standard SQL:2008 |
| **MySQL 5.7+** | `INSERT ... ON DUPLICATE KEY UPDATE` | Native MySQL syntax |
| **MariaDB 10.3+** | `INSERT ... ON DUPLICATE KEY UPDATE` | Compatible with MySQL |
| **PostgreSQL 9.5+** | `INSERT ... ON CONFLICT() DO UPDATE SET` | Native PostgreSQL |

### Key Assumption
All index tables have a **UNIQUE constraint** on `case_instance_id`, which the UPSERT uses as the conflict key. This is created in [`CaseDataWorker.createDefaultWorkTable()`](core/src/main/java/vn/com/fecredit/flowable/exposer/job/CaseDataWorker.java:811):

```sql
CREATE TABLE case_plain_order (
    id VARCHAR(255) PRIMARY KEY,
    case_instance_id VARCHAR(255) NOT NULL UNIQUE,  -- Conflict key
    plain_payload LONGTEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ...
);
```

---

## Testing Database Portability

### Current Test Suite
- **Unit Tests**: All metadata resolution and indexing logic (dialect-agnostic)
- **Integration Tests**: H2 in-memory database (CI/CD standard)
- **E2E Tests**: Full workflow with CMMN cases and BPMN processes

### To Test on MySQL/PostgreSQL
Update `application.properties` or `application-test.properties`:

**For MySQL**:
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/flowable_test
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.jpa.database-platform=org.hibernate.dialect.MySQL8Dialect
```

**For PostgreSQL**:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/flowable_test
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQL10Dialect
```

No source code changes needed — `DbDialectProvider` auto-detects at runtime.

---

## Architecture Diagram

```
CaseDataWorker (UPSERT operations)
        ↓
DbDialectProvider (Detect DB + Generate SQL)
        ↓
EntityManager.createNativeQuery()
        ↓
Spring Data JPA / Hibernate
        ↓
(H2 | MySQL/MariaDB | PostgreSQL)
```

---

## Performance Considerations

### Native Queries vs ORM
- **DbAccessService uses native SQL** for performance-critical operations (batch upserts, DDL)
- Native queries avoid ORM overhead for dynamic table access (tables determined at runtime)
- Parameterized queries still enforce security (no SQL injection)

### Connection Pooling
- JPA/Hibernate manages HikariCP connection pool
- Configured via `spring.datasource.hikari.*` properties
- No manual connection management required

---

## Future Improvements

### Phase 2: Selective Entity Mapping
If performance analysis shows benefit, create compile-time entities for common work tables:
```java
@Entity
@Table(name = "case_plain_order", uniqueConstraints = @UniqueConstraint(name = "uk_case_id", columnNames = "case_instance_id"))
public class CasePlainOrder { ... }
```

Then use Spring Data JPA repositories for those tables while keeping dynamic tables for less-frequent types.

### Phase 3: Hibernate Dialect Abstraction
Replace `DbDialectProvider` with Hibernate's built-in dialect system:
```java
SessionFactory sessionFactory = em.unwrap(Session.class).getSessionFactory();
Dialect dialect = sessionFactory.getJdbcServices().getDialect();
```

This requires Hibernate 6.2+; currently skipped due to API stability concerns.

---

## References

- [Hibernate Dialects](https://hibernate.org/orm/documentation/)
- [Spring Data JPA Native Queries](https://spring.io/projects/spring-data-jpa)
- [EntityManager.createNativeQuery() JavaDoc](https://jakarta.ee/specifications/persistence/3.0/apidocs/jakarta.persistence/entitymanager)
- [JPA Performance Best Practices](https://thorben-janssen.com/best-practices/)
