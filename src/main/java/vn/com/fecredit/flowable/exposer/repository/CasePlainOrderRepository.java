package vn.com.fecredit.flowable.exposer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.fecredit.flowable.exposer.entity.CasePlainOrder;

import java.util.Optional;

public interface CasePlainOrderRepository extends JpaRepository<CasePlainOrder, Long> {
    Optional<CasePlainOrder> findByCaseInstanceId(String caseInstanceId);

    default CasePlainOrder upsertByCaseInstanceId(String caseInstanceId, java.util.function.Consumer<CasePlainOrder> mutator, CasePlainOrderRepository repo) {
        CasePlainOrder ent = repo.findByCaseInstanceId(caseInstanceId).orElseGet(() -> {
            CasePlainOrder p = new CasePlainOrder(); p.setCaseInstanceId(caseInstanceId); return p;
        });
        mutator.accept(ent);
        return repo.save(ent);
    }
}
