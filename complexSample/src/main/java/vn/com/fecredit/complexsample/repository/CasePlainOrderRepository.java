package vn.com.fecredit.complexsample.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.fecredit.complexsample.entity.CasePlainOrder;

import java.util.Optional;

/**
 * Repository for `CasePlainOrder` — provides quick lookup and an atomic
 * upsert helper used by the {@code CaseDataWorker} to persist plain rows.
 */
public interface
CasePlainOrderRepository extends JpaRepository<CasePlainOrder, Long> {
    /**
     * Find a plain-order row by its Flowable case instance id.
     */
    Optional<CasePlainOrder> findByCaseInstanceId(String caseInstanceId);

    /**
     * Upsert a `CasePlainOrder` by caseInstanceId. The provided {@code mutator}
     * will be applied to the found-or-new entity and the result saved.
     *
     * This method is convenient for callers that want an atomic read-modify-
     * save operation without duplicating boilerplate.
     */
    default CasePlainOrder upsertByCaseInstanceId(String caseInstanceId, java.util.function.Consumer<CasePlainOrder> mutator, CasePlainOrderRepository repo) {
        CasePlainOrder ent = repo.findByCaseInstanceId(caseInstanceId).orElseGet(() -> {
            CasePlainOrder p = new CasePlainOrder(); p.setCaseInstanceId(caseInstanceId); return p;
        });
        mutator.accept(ent);
        return repo.save(ent);
    }
}
