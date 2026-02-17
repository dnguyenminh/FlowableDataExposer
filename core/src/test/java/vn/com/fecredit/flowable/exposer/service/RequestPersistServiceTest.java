package vn.com.fecredit.flowable.exposer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;
import vn.com.fecredit.flowable.exposer.entity.SysExposeRequest;
import vn.com.fecredit.flowable.exposer.repository.SysExposeRequestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RequestPersistServiceTest {

    private SysExposeRequestRepository repo;
    private RequestPersistService svc;

    @BeforeEach
    void setUp() {
        repo = mock(SysExposeRequestRepository.class);
        svc = new RequestPersistService();
        // inject mock via reflection
        try {
            var f = RequestPersistService.class.getDeclaredField("requestRepo");
            f.setAccessible(true);
            f.set(svc, repo);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createRequest_saves_entity_and_uses_saveAndFlush() {
        when(repo.saveAndFlush(org.mockito.ArgumentMatchers.any(SysExposeRequest.class)))
                .thenAnswer(i -> {
                    SysExposeRequest r = i.getArgument(0);
                    r.setId(42L);
                    return r;
                });

        svc.createRequest("case-1", "Order", "user1");

        ArgumentCaptor<SysExposeRequest> cap = ArgumentCaptor.forClass(SysExposeRequest.class);
        verify(repo).saveAndFlush(cap.capture());
        SysExposeRequest saved = cap.getValue();
        assertThat(saved.getCaseInstanceId()).isEqualTo("case-1");
        assertThat(saved.getEntityType()).isEqualTo("Order");
        assertThat(saved.getRequestedBy()).isEqualTo("user1");
    }

    @Test
    void createRequest_rethrows_and_logs_on_failure() {
        when(repo.saveAndFlush(org.mockito.ArgumentMatchers.any(SysExposeRequest.class)))
                .thenThrow(new DataAccessException("db down") {});

        assertThatThrownBy(() -> svc.createRequest("case-2", "Order", null))
                .isInstanceOf(Exception.class);
    }
}
