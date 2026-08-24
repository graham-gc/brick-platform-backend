package apiworkflow.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrickTestSuiteMapperXmlTest {

    @Test
    void suiteMappersDefineEveryDeclaredStatement() throws Exception {
        Configuration configuration = new Configuration();
        parse(configuration, "mapper/BrickTestSuiteMapper.xml");
        parse(configuration, "mapper/BrickTestSuiteFlowMappingMapper.xml");
        parse(configuration, "mapper/BrickTestSuiteRunMapper.xml");

        assertStatement(configuration, "apiworkflow.mapper.BrickTestSuiteMapper.countBySwaggerMappingId");
        assertStatement(configuration, "apiworkflow.mapper.BrickTestSuiteFlowMappingMapper.selectBySuiteId");
        assertStatement(configuration, "apiworkflow.mapper.BrickTestSuiteFlowMappingMapper.batchInsert");
        assertStatement(configuration, "apiworkflow.mapper.BrickTestSuiteFlowMappingMapper.deleteBySuiteId");
        assertStatement(configuration, "apiworkflow.mapper.BrickTestSuiteRunMapper.selectById");
        assertStatement(configuration, "apiworkflow.mapper.BrickTestSuiteRunMapper.insert");
        assertStatement(configuration, "apiworkflow.mapper.BrickTestSuiteRunMapper.updateById");
        assertStatement(configuration, "apiworkflow.mapper.BrickTestSuiteRunMapper.selectRunsByCondition");
        assertStatement(configuration, "apiworkflow.mapper.BrickTestSuiteRunMapper.countRunsByCondition");
    }

    private void parse(Configuration configuration, String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource + " must be available on the classpath");
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private void assertStatement(Configuration configuration, String id) {
        assertTrue(configuration.hasStatement(id), "Missing MyBatis statement: " + id);
    }
}
