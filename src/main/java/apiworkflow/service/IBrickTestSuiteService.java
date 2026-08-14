package apiworkflow.service;

import apiworkflow.entity.BrickTestSuite;
import apiworkflow.entity.BrickTestSuiteRun;
import apiworkflow.entity.BrickTestSuiteFlowMapping;
import java.util.List;
import java.util.Map;

public interface IBrickTestSuiteService {

    int createTestSuite(BrickTestSuite suite, List<BrickTestSuiteFlowMapping> flowMappings, String operator);

    int updateTestSuite(BrickTestSuite suite, List<BrickTestSuiteFlowMapping> flowMappings, String operator);

    int deleteTestSuite(Integer id, String operator);

    List<BrickTestSuite> listTestSuitesBySwaggerMappingId(Integer swaggerMappingId, String keyword, int offset, int pageSize);

    int countTestSuitesBySwaggerMappingId(Integer swaggerMappingId, String keyword);

    Map<String, Object> getTestSuiteDetail(Integer id);

    BrickTestSuiteRun runTestSuite(Integer id, String operator, Map<String, String> customHeaders);

    BrickTestSuiteRun rerunFailedFlows(Long suiteRunId, String operator);

    List<Map<String, Object>> listRunsBySwaggerMappingId(Integer testSuiteId, Integer swaggerMappingId,
                                                           String suiteName, String status,
                                                           String startDate, String endDate, int offset, int pageSize);

    int countRunsBySwaggerMappingId(Integer testSuiteId, Integer swaggerMappingId,
                                     String suiteName, String status, String startDate, String endDate);

    Map<String, Object> getRunDetail(Long runId);

    Map<String, Object> checkAndFixInterruption(Long runId);

    Map<String, Object> getFlowResultsSummary(Long runId);
}