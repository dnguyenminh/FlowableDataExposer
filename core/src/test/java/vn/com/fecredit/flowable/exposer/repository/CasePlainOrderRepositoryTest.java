package vn.com.fecredit.flowable.exposer.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import vn.com.fecredit.flowable.exposer.entity.CasePlainOrder;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class CasePlainOrderRepositoryTest {

    @Autowired
    CasePlainOrderRepository repo;

    @Test
    void upsert_createsEntityWhenMissing() {
        CasePlainOrder created = repo.upsertByCaseInstanceId("case-1", p -> p.setOrderTotal(50.0), repo);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getOrderTotal()).isEqualTo(50.0);

        var found = repo.findByCaseInstanceId("case-1");
        assertThat(found).isPresent();
        assertThat(found.get().getOrderTotal()).isEqualTo(50.0);
    }

    @Test
    void upsert_updatesExistingEntity() {
        CasePlainOrder base = new CasePlainOrder();
        base.setCaseInstanceId("case-2");
        base.setOrderTotal(10.0);
        base = repo.save(base);

        CasePlainOrder updated = repo.upsertByCaseInstanceId("case-2", p -> p.setOrderTotal(20.0), repo);
        assertThat(updated.getId()).isEqualTo(base.getId());
        assertThat(updated.getOrderTotal()).isEqualTo(20.0);
    }
}
