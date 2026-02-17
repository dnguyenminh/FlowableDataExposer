# COMPREHENSIVE TEST FIX STRATEGY - FlowableDataExposer

## Executive Summary
- **Total Tests:** 76 (56 passing, 20 failing)
- **Analysis Date:** February 16, 2026
- **Root Causes Identified:** 3 distinct categories
- **Fixability Assessment:** 4/20 tests are fixable; 16 require framework-level changes

---

## FAILURE CATEGORIES & REMEDIATION ROADMAP

### CATEGORY 1: Spring Context Initialization (14 Tests) - FRAMEWORK ISSUE
**Status:** Pre-existing, requires architectural changes
**Affected Tests:**
- CaseDataWorkerUnitTest
- MetadataAnnotatorTest
- MetadataAutoCreateColumnTest
- MetadataBaseClassesTest
- MetadataChildRemoveReaddTest
- MetadataCycleDetectionTest
- MetadataDdlFromResolverTest
- MetadataDiagnosticsTest (both tests)
- MetadataInheritanceTest
- MetadataInspectTest
- MetadataMixinE2eTest
- MetadataMultipleInheritanceTest
- MetadataResolverIndexMapAccessTest

**Root Cause:**
```
java.lang.IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:145
Caused by: JPA Metamodel initialization not completing during @SpringBootTest context bootstrap
```

**Why It's a Framework Issue:**
- Tests use `@SpringBootTest(classes = FlowableExposerTestApplicationFinal.class)`
- Spring Boot 3.5.10 with JPA requires full entity metamodel to be available
- Multiple metadata resolvers and repositories need to initialize in correct order
- H2 dialect configuration may need adjustment

**Required Fixes:**
1. Review `FlowableExposerTestApplicationFinal` configuration
2. Add `@EntityScan` and `@EnableJpaRepositories` with explicit packages
3. Consider test profile configuration (application-test.properties)
4. May need to split test suite into smaller context groups
5. Verify all `@Entity` classes are discoverable by Spring

**Recommendation:** Create separate Sprint for "Spring Boot 3.5.10 Compatibility" to address JPA metamodel initialization

---

### CATEGORY 2: Bean Definition Conflicts (2 Tests) - CONFIGURATION ISSUE
**Status:** Fixable with configuration changes
**Affected Tests:**
- CaseDataPersistServiceIntegrationTest
- MetadataDbOverrideTest

**Root Cause:**
```
org.springframework.beans.factory.support.BeanDefinitionOverrideException
```

**Why It Occurs:**
- Multiple bean definitions with same name
- Likely duplicate `@Component` or `@Service` beans in classpath
- Hybrid source approach (core/src + canonical ../src) may create duplication

**Quick Fix:**
1. Use `@Primary` annotation on preferred bean
2. Rename duplicate bean definitions
3. Add Spring configuration to allow bean override (not recommended)

**Required Fixes:**
1. Audit all `@Component`, `@Service`, `@Repository` annotations
2. Search for duplicate bean names in core/src and ../src
3. Add `@Qualifier` annotations for disambiguation
4. Configure `spring.main.allow-bean-definition-overriding=false` (strict mode)

---

### CATEGORY 3: Assertion Failures (4 Tests) - FIXABLE

#### Test 3A: MetadataResourceLoaderTest
**Status:** PARTIALLY FIXABLE
**Error:** AssertionError - loader cannot find "WorkObject"
**Root Cause:** Metadata file validation is strict; created WorkObject.json has invalid field names
**Fix:** Update WorkObject.json to match MetadataDefinition expected fields

#### Test 3B: ModelImageHelpersTest - isMostlyBlank_falseForSingleDarkPixel
**Status:** FIXABLE
**Error:** AssertionFailedError at line 87
**Root Cause:** Test expects single dark pixel to trigger "not blank"; threshold logic may be inverted
**Fix:** Adjust isMostlyBlank() logic to correctly detect minority dark pixels

#### Test 3C: IndexJobControllerTest
**Status:** PARTIALLY FIXABLE
**Error:** NoClassDefFoundError - missing class at runtime
**Root Cause:** com.networknt.schema.Schema classes not loaded properly in test context
**Fix:** Ensure networknt:json-schema-validator is properly scoped

#### Test 3D: MetadataControllerTest
**Status:** REQUIRES INVESTIGATION
**Error:** AssertionFailedError at line 34
**Root Cause:** Controller response validation failure
**Fix:** Review test expectations vs actual controller response structure

---

## IMPLEMENTATION PRIORITY

### TIER 1: Quick Wins (Can fix immediately)
1. ✅ **SysExposeClassDefTest** - ALREADY FIXED (Jackson JSR310)
2. **MetadataResourceLoaderTest** - Fix WorkObject.json field names
3. **ModelImageHelpersTest** - Adjust blank detection threshold

### TIER 2: Configuration Fixes (Requires architectural changes)
4. **CaseDataPersistServiceIntegrationTest** - Bean definition audit
5. **MetadataDbOverrideTest** - Bean definition audit

### TIER 3: Framework-Level (Large effort, separate sprint)
6-19. **14 Spring Context Tests** - JPA Metamodel initialization
20. **IndexJobControllerTest** - Classpath/module configuration
21. **MetadataControllerTest** - Deep investigation needed

---

## RECOMMENDED ACTION PLAN

### Phase 1: Immediate (Today)
- [x] Fix SysExposeClassDefTest (DONE)
- [ ] Fix MetadataResourceLoaderTest (30 mins)
- [ ] Fix ModelImageHelpersTest (30 mins)
- [ ] Investigate MetadataControllerTest (1 hour)

**Expected Outcome:** 3-4 additional tests fixed

### Phase 2: Short-term (This week)
- [ ] Audit bean definitions for duplicates
- [ ] Fix CaseDataPersistServiceIntegrationTest & MetadataDbOverrideTest
- [ ] Create test profile configuration

**Expected Outcome:** 2 additional tests fixed

### Phase 3: Long-term (Next sprint)
- [ ] Refactor Spring test context strategy
- [ ] Split test classes to avoid metamodel bloat
- [ ] Create focused test suites per module

**Expected Outcome:** Fix 14 Spring context tests (7-10 day effort)

---

## CURRENT PASS RATE TRAJECTORY

```
Initial:     51/76 passing (67%)
After Phase 1 fixes: 59-60/76 passing (78-79%)
After Phase 2 fixes: 61-62/76 passing (80-82%)
After Phase 3 fixes: 75-76/76 passing (99%)
```

---

## TECHNICAL DEBT & ARCHITECTURE NOTES

### Hybrid Source Configuration Issue
The project uses:
- `core/src/main` + canonical `../src/main` (both on classpath)
- `core/src/test` + canonical `../src/test` (both on classpath)
- This can cause duplicate bean definitions and classpath conflicts

### Recommended Refactoring
1. Consolidate source paths (eliminate duplication)
2. Use Spring profiles for test-specific beans
3. Create standalone test application configuration
4. Separate unit tests (no Spring) from integration tests (with Spring)

---

## CONCLUSION

**Immediate Action:** Fix 3-4 tests in Category 3 (assertion failures)
**Root Problem:** Architectural issue with Spring context and JPA metamodel initialization
**Long-term Solution:** Refactor Spring test configuration and eliminate source path duplication


