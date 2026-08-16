package apiworkflow.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrickFlowRunMapperXmlTest {

    private static final String NAMESPACE = "apiworkflow.mapper.BrickFlowRunMapper.";

    @Test
    void mapperDefinesRunListAndCountStatements() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/BrickFlowRunMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "BrickFlowRunMapper.xml must be available on the classpath");
            XMLMapperBuilder builder = new XMLMapperBuilder(
                    input, configuration, resource, configuration.getSqlFragments());
            builder.parse();
        }

        assertTrue(configuration.hasStatement(NAMESPACE + "selectRunsBySwaggerMappingId"));
        assertTrue(configuration.hasStatement(NAMESPACE + "countRunsBySwaggerMappingId"));
        assertTrue(configuration.hasStatement(NAMESPACE + "selectRunsByFlowId"));
        assertTrue(configuration.hasStatement(NAMESPACE + "countRunsByFlowId"));
    }
}
