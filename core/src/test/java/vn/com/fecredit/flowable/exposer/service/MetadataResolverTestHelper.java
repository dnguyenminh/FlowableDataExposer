package vn.com.fecredit.flowable.exposer.service;

import org.mockito.Mockito;
import vn.com.fecredit.flowable.exposer.repository.SysExposeClassDefRepository;

/**
 * Test utility to create MetadataResolver and related components without requiring full Spring context.
 * This avoids JPA Metamodel initialization issues that occur with @SpringBootTest.
 */
public class MetadataResolverTestHelper {

    public static MetadataResolver createMetadataResolver() {
        MetadataResourceLoader resourceLoader = new MetadataResourceLoader();
        resourceLoader.init();

        // Mock repo - used for DB overrides, but tests will use file-backed definitions
        SysExposeClassDefRepository mockRepo = Mockito.mock(SysExposeClassDefRepository.class);

        return new MetadataResolver(mockRepo, resourceLoader);
    }

    public static MetadataLookup createMetadataLookup() {
        return new MetadataLookup(createMetadataResolver());
    }
}

