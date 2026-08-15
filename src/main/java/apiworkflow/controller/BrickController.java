package apiworkflow.controller;

import apiworkflow.dto.*;
import apiworkflow.entity.*;
import apiworkflow.service.*;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/brick")
public class BrickController {

    @Autowired
    private IBrickApiService apiService;

    @Autowired
    private IBrickFlowService flowService;

    @Autowired
    private IBrickTestSuiteService testSuiteService;

    @Autowired
    private IGlobalVariableService globalVariableService;

    // ==================== Swagger Mapping APIs ====================

    @GetMapping("/mappings")
    public ApiResponse<List<AppSwaggerMapping>> getMappings(AppSwaggerMapping query) {
        List<AppSwaggerMapping> list = apiService.selectList(query);
        return ApiResponse.success(list);
    }

    @GetMapping("/mappings/{id}")
    public ApiResponse<AppSwaggerMapping> getMappingById(@PathVariable Integer id) {
        AppSwaggerMapping mapping = apiService.getById(id);
        return ApiResponse.success(mapping);
    }

    @PostMapping("/mappings")
    public ApiResponse<Integer> createMapping(@RequestBody AppSwaggerMapping record) {
        int result = apiService.createMapping(record);
        if (result != 1 || record.getId() == null) {
            return ApiResponse.error("Failed to create Swagger mapping");
        }
        return ApiResponse.success(record.getId());
    }

    @PostMapping("/mappings/update")
    public ApiResponse<Integer> updateMapping(@RequestBody AppSwaggerMapping record) {
        int result = apiService.updateMapping(record);
        return ApiResponse.success(result);
    }

    @PostMapping("/mappings/delete/{id}")
    public ApiResponse<Integer> deleteMapping(@PathVariable Integer id,
                                               @RequestParam(required = false) String operator) {
        int result = apiService.softDeleteMapping(id, operator);
        return ApiResponse.success(result);
    }

    @GetMapping("/mappings/versions")
    public ApiResponse<List<AppSwaggerMapping>> getVersions(
            @RequestParam String env, @RequestParam String appConfigId) {
        List<AppSwaggerMapping> list = apiService.getVersionsByEnvAndAppConfig(env, appConfigId);
        return ApiResponse.success(list);
    }

    @GetMapping("/mappings/version")
    public ApiResponse<AppSwaggerMapping> getMappingByVersion(
            @RequestParam String env, @RequestParam String appConfigId, @RequestParam String versionTag) {
        AppSwaggerMapping mapping = apiService.getByEnvAppConfigAndVersion(env, appConfigId, versionTag);
        return ApiResponse.success(mapping);
    }

    // ==================== Swagger Sync APIs ====================

    @PostMapping("/validate-and-parse")
    public ApiResponse<Map<String, Object>> validateAndParse(@RequestBody ValidateParseRequest request) {
        String jsonContent = request.getSwaggerFileContent();
        if (jsonContent == null && request.getSwaggerUrl() != null) {
            jsonContent = apiService.detectAndFetchSwaggerJson(request.getSwaggerUrl());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("valid", apiService.isValidSwaggerJson(jsonContent));
        result.put("type", apiService.validateSwaggerJsonDetailed(jsonContent));
        result.put("swaggerContent", jsonContent);
        return ApiResponse.success(result);
    }

    @PostMapping("/sync")
    public ApiResponse<Map<String, Object>> sync(@RequestBody SyncRequest request) {
        AppSwaggerMapping mapping = request.getSwaggerMappingId() == null
                ? apiService.getByEnvAppConfigAndVersion(
                        request.getEnv(), request.getAppConfigId(), request.getVersionTag())
                : apiService.getById(request.getSwaggerMappingId());

        if (mapping == null) {
            return ApiResponse.error("Swagger mapping not found");
        }

        int count = apiService.syncEndpointDefinitionsBySwaggerMappingId(
                mapping.getId(), request.getSwaggerContent(), request.getOperator(),
                request.getCustomHost(), request.getCustomBasePath());

        Map<String, Object> result = new HashMap<>();
        result.put("endpointCount", count);
        result.put("mappingId", mapping.getId());
        result.put("versionTag", request.getVersionTag());
        return ApiResponse.success(result);
    }

    // ==================== Endpoint APIs ====================

    @GetMapping("/endpoints")
    public ApiResponse<Map<String, Object>> getEndpoints(
            @RequestParam Integer swaggerMappingId,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        int offset = (pageNum - 1) * pageSize;
        List<EndpointDefinition> rows = apiService.selectEndpointPageBySwaggerMappingId(
                swaggerMappingId, method, keyword, offset, pageSize);
        int total = apiService.countEndpointsBySwaggerMappingId(swaggerMappingId, method, keyword);

        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        result.put("total", total);
        return ApiResponse.success(result);
    }

    @GetMapping("/endpoints/all")
    public ApiResponse<List<EndpointDefinition>> getAllEndpoints(@RequestParam Integer swaggerMappingId) {
        List<EndpointDefinition> list = apiService.selectEndpointPageBySwaggerMappingId(
                swaggerMappingId, null, null, 0, Integer.MAX_VALUE);
        return ApiResponse.success(list);
    }

    @GetMapping("/endpoints/{id}")
    public ApiResponse<Map<String, Object>> getEndpointDetail(@PathVariable Integer id) {
        Map<String, Object> detail = apiService.getEndpointDetail(id);
        return ApiResponse.success(detail);
    }

    @PostMapping("/endpoints/stats")
    public ApiResponse<Map<Integer, Map<String, Object>>> getEndpointStats(
            @RequestBody Map<String, Object> request) {
        List<Integer> ids = (List<Integer>) request.get("swaggerMappingIds");
        String keyword = (String) request.get("keyword");
        Map<Integer, Map<String, Object>> stats = apiService.getBatchEndpointStats(ids, keyword);
        return ApiResponse.success(stats);
    }

    // ==================== Flow APIs ====================

    @GetMapping("/flows")
    public ApiResponse<Map<String, Object>> getFlows(
            BrickFlow query,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer endpointId,
            @RequestParam(required = false) Integer grpcEndpointId,
            @RequestParam(required = false) String executionStatus) {

        int offset = (pageNum - 1) * pageSize;
        List<BrickFlow> rows = flowService.listFlows(query, offset, pageSize, endpointId, grpcEndpointId, executionStatus);
        int total = flowService.countFlows(query, endpointId, grpcEndpointId, executionStatus);

        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        result.put("total", total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        return ApiResponse.success(result);
    }

    @GetMapping("/flows/{id}")
    public ApiResponse<Map<String, Object>> getFlowDetail(@PathVariable Integer id) {
        Map<String, Object> detail = flowService.getFlowDetail(id);
        return ApiResponse.success(detail);
    }

    @GetMapping("/full/flows/{id}")
    public ApiResponse<Map<String, Object>> getFullFlowDetail(@PathVariable Integer id) {
        Map<String, Object> detail = flowService.getFullFlowDetail(id);
        return ApiResponse.success(detail);
    }

    @PostMapping("/flows")
    public ApiResponse<BrickFlow> createFlow(@RequestBody FlowUpsertReq request) {
        BrickFlow flow = request.getFlow();
        int result = flowService.createFlow(flow, request.getNodes(), request.getEdges(), request.getOperator());
        return ApiResponse.success(flow);
    }

    @PostMapping("/full/flows")
    public ApiResponse<BrickFlow> createFullFlow(@RequestBody FlowUpsertReq request) {
        BrickFlow flow = request.getFlow();
        flowService.createFullFlow(flow, request.getFullNodes(), request.getEdges(), request.getOperator());
        return ApiResponse.success(flow);
    }

    @PostMapping("/flows/update")
    public ApiResponse<Integer> updateFlow(@RequestBody FlowUpsertReq request) {
        int result = flowService.updateFlow(request.getFlow(), request.getNodes(), request.getEdges(), request.getOperator());
        return ApiResponse.success(result);
    }

    @PostMapping("/flows/delete/{id}")
    public ApiResponse<Integer> deleteFlow(@PathVariable Integer id,
                                            @RequestParam(required = false) String operator) {
        int result = flowService.deleteFlow(id, operator);
        return ApiResponse.success(result);
    }

    @PostMapping("/flows/hard-delete-batch")
    public ApiResponse<Integer> hardDeleteFlows(@RequestBody List<Integer> ids) {
        int result = flowService.hardDeleteFlows(ids);
        return ApiResponse.success(result);
    }

    @PostMapping("/flows/{flowId}/copy")
    public ApiResponse<Map<String, Object>> copyFlow(@PathVariable Integer flowId,
                                                      @RequestBody CopyFlowRequest request) {
        int newFlowId = flowService.copyFlow(flowId, request.getNewName(),
                request.getTargetEnv(), request.getOperator(), request.getTargetSwaggerMappingId());

        Map<String, Object> result = new HashMap<>();
        result.put("sourceFlowId", flowId);
        result.put("newFlowId", newFlowId);
        result.put("newName", request.getNewName());
        return ApiResponse.success(result);
    }

    @PostMapping("/flows/copy/batch")
    public ApiResponse<Map<String, Object>> batchCopyFlows(@RequestBody BatchCopyFlowRequest request) {
        Map<String, Object> result = flowService.batchCopyFlows(
                request.getFlowCopyItems(), request.getTargetEnv(), request.getOperator());
        return ApiResponse.success(result);
    }

    // ==================== Flow Run APIs ====================

    @PostMapping("/flows/{id}/run")
    public ApiResponse<BrickFlowRun> runFlow(@PathVariable Integer id,
                                              @RequestBody RunFlowReq request) {
        BrickFlowRun run = flowService.runFlow(id, request.getOperator(),
                request.getOverrideBaseUrl(), request.getRunType());
        return ApiResponse.success(run);
    }

    @PostMapping("/flows/{id}/run-custom")
    public ApiResponse<BrickFlowRun> runFlowCustom(@PathVariable Integer id,
                                                    @RequestBody RunFlowCustomReq request) {
        BrickFlowRun run = flowService.runFlowWithCustomNodeParams(id, null,
                request.getOverrideBaseUrl(), request.getRunType(), request.getNodeId(),
                request.getEndpointId(), request.getGrpcEndpointId(), request.getCustomParams());
        return ApiResponse.success(run);
    }

    @PostMapping("/flows/batch-run")
    public ApiResponse<Map<String, Object>> batchRunFlows(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Integer> flowIds = (List<Integer>) request.get("flowIds");
        String overrideBaseUrl = (String) request.get("overrideBaseUrl");

        Map<String, Object> result = new HashMap<>();
        result.put("batchId", System.currentTimeMillis());
        result.put("totalCount", flowIds.size());
        return ApiResponse.success(result);
    }

    @GetMapping("/flows/runs/{runId}")
    public ApiResponse<Map<String, Object>> getRunDetail(@PathVariable Long runId) {
        Map<String, Object> detail = flowService.getRunDetail(runId);
        return ApiResponse.success(detail);
    }

    @GetMapping("/flows/runs")
    public ApiResponse<Map<String, Object>> getRuns(
            @RequestParam Integer swaggerMappingId,
            @RequestParam(required = false) Integer flowId,
            @RequestParam(required = false) String flowName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) Long testSuiteRunId,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) Integer runType,
            @RequestParam(defaultValue = "false") Boolean distinctLatest,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        int offset = (pageNum - 1) * pageSize;
        List<Map<String, Object>> rows = flowService.listRunsBySwaggerMappingId(
                swaggerMappingId, flowName, null, status, startDate, endDate,
                testSuiteRunId, batchId, runType, distinctLatest, offset, pageSize);
        int total = flowService.countRunsBySwaggerMappingId(
                swaggerMappingId, flowName, null, status, startDate, endDate,
                testSuiteRunId, batchId, runType, distinctLatest);

        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        result.put("total", total);
        return ApiResponse.success(result);
    }

    @GetMapping("/run/{runId}/node/{nodeId}/detail")
    public ApiResponse<BrickFlowRunNode> getNodeExecutionDetail(
            @PathVariable Long runId, @PathVariable Long nodeId) {
        BrickFlowRunNode node = flowService.getNodeExecutionDetail(runId, nodeId);
        return ApiResponse.success(node);
    }

    @GetMapping("/flows/{flowId}/interfaces")
    public ApiResponse<Map<String, Object>> getFlowInterfaces(@PathVariable Integer flowId) {
        Map<String, Object> detail = flowService.getFlowDetail(flowId);
        Map<String, Object> result = new HashMap<>();
        result.put("interfaces", detail.get("nodes"));
        return ApiResponse.success(result);
    }

    // ==================== Test Suite APIs ====================

    @GetMapping("/test-suites")
    public ApiResponse<Map<String, Object>> getTestSuites(
            @RequestParam Integer swaggerMappingId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        int offset = (pageNum - 1) * pageSize;
        List<BrickTestSuite> list = testSuiteService.listTestSuitesBySwaggerMappingId(
                swaggerMappingId, keyword, offset, pageSize);
        int total = testSuiteService.countTestSuitesBySwaggerMappingId(swaggerMappingId, keyword);

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", total);
        result.put("pages", (total + pageSize - 1) / pageSize);
        return ApiResponse.success(result);
    }

    @GetMapping("/test-suites/{id}")
    public ApiResponse<Map<String, Object>> getTestSuiteDetail(@PathVariable Integer id) {
        Map<String, Object> detail = testSuiteService.getTestSuiteDetail(id);
        return ApiResponse.success(detail);
    }

    @PostMapping("/test-suites")
    public ApiResponse<Integer> createTestSuite(@RequestBody TestSuiteUpsertReq request) {
        int result = testSuiteService.createTestSuite(
                request.getSuite(), request.getFlowMappings(), request.getOperator());
        return ApiResponse.success(result);
    }

    @PostMapping("/test-suites/update")
    public ApiResponse<Integer> updateTestSuite(@RequestBody TestSuiteUpsertReq request) {
        int result = testSuiteService.updateTestSuite(
                request.getSuite(), request.getFlowMappings(), request.getOperator());
        return ApiResponse.success(result);
    }

    @PostMapping("/test-suites/delete/{id}")
    public ApiResponse<Integer> deleteTestSuite(@PathVariable Integer id) {
        int result = testSuiteService.deleteTestSuite(id, null);
        return ApiResponse.success(result);
    }

    @PostMapping("/test-suites/{id}/run")
    public ApiResponse<BrickTestSuiteRun> runTestSuite(@PathVariable Integer id,
                                                        @RequestParam(required = false) String operator) {
        BrickTestSuiteRun run = testSuiteService.runTestSuite(id, operator, null);
        return ApiResponse.success(run);
    }

    @GetMapping("/test-suites/runs")
    public ApiResponse<Map<String, Object>> getTestSuiteRuns(
            @RequestParam(required = false) Integer testSuiteId,
            @RequestParam(required = false) Integer swaggerMappingId,
            @RequestParam(required = false) String suiteName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        int offset = (pageNum - 1) * pageSize;
        List<Map<String, Object>> rows = testSuiteService.listRunsBySwaggerMappingId(
                testSuiteId, swaggerMappingId, suiteName, status, startDate, endDate, offset, pageSize);
        int total = testSuiteService.countRunsBySwaggerMappingId(
                testSuiteId, swaggerMappingId, suiteName, status, startDate, endDate);

        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        result.put("total", total);
        return ApiResponse.success(result);
    }

    @GetMapping("/test-suites/runs/{runId}")
    public ApiResponse<Map<String, Object>> getTestSuiteRunDetail(@PathVariable Long runId) {
        Map<String, Object> detail = testSuiteService.getRunDetail(runId);
        return ApiResponse.success(detail);
    }

    @PostMapping("/test-suites/rerunFailed")
    public ApiResponse<BrickTestSuiteRun> rerunFailed(@RequestBody Map<String, Object> request) {
        Long suiteRunId = ((Number) request.get("suiteRunId")).longValue();
        String operator = (String) request.get("operator");
        BrickTestSuiteRun run = testSuiteService.rerunFailedFlows(suiteRunId, operator);
        return ApiResponse.success(run);
    }

    @PostMapping("/test-suites/runs/{runId}/checkInterruption")
    public ApiResponse<Map<String, Object>> checkInterruption(@PathVariable Long runId) {
        Map<String, Object> result = testSuiteService.checkAndFixInterruption(runId);
        return ApiResponse.success(result);
    }

    // ==================== Global Variable APIs ====================

    @GetMapping("/global-variables")
    public ApiResponse<List<BrickGlobalVariable>> getGlobalVariables(BrickGlobalVariable query) {
        List<BrickGlobalVariable> list = globalVariableService.selectList(query);
        return ApiResponse.success(list);
    }

    @GetMapping("/global-variables/{id}")
    public ApiResponse<BrickGlobalVariable> getGlobalVariableById(@PathVariable Long id) {
        BrickGlobalVariable variable = globalVariableService.getById(id);
        return ApiResponse.success(variable);
    }

    @PostMapping("/global-variables")
    public ApiResponse<Integer> createGlobalVariable(
            @RequestBody Map<String, Object> request,
            @RequestParam String operator) {
        BrickGlobalVariable variable = JSON.parseObject(JSON.toJSONString(request.get("variable")), BrickGlobalVariable.class);
        int result = globalVariableService.create(variable, operator);
        return ApiResponse.success(result);
    }

    @PostMapping("/global-variables/update")
    public ApiResponse<Integer> updateGlobalVariable(@RequestBody Map<String, Object> request) {
        BrickGlobalVariable variable = JSON.parseObject(JSON.toJSONString(request.get("variable")), BrickGlobalVariable.class);
        String operator = (String) request.get("operator");
        int result = globalVariableService.update(variable, operator);
        return ApiResponse.success(result);
    }

    @PostMapping("/global-variables/delete/{id}")
    public ApiResponse<Integer> deleteGlobalVariable(@PathVariable Long id) {
        int result = globalVariableService.delete(id);
        return ApiResponse.success(result);
    }
}
