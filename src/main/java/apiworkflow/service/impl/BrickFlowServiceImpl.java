package apiworkflow.service.impl;

import apiworkflow.dto.BrickFlowFullNode;
import apiworkflow.entity.*;
import apiworkflow.mapper.*;
import apiworkflow.service.IBrickFlowService;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class BrickFlowServiceImpl implements IBrickFlowService {

    @Autowired
    private BrickFlowMapper flowMapper;

    @Autowired
    private BrickFlowNodeMapper nodeMapper;

    @Autowired
    private BrickFlowEdgeMapper edgeMapper;

    @Autowired
    private BrickFlowRunMapper runMapper;

    @Autowired
    private BrickFlowRunNodeMapper runNodeMapper;

    @Override
    public BrickFlow getFlow(Integer id) {
        return flowMapper.selectById(id);
    }

    @Override
    public List<BrickFlow> listFlows(BrickFlow query, int offset, int limit,
                                      Integer endpointId, Integer grpcEndpointId, String executionStatus) {
        if (endpointId != null) {
            return flowMapper.selectPageByEndpoint(query, offset, limit, endpointId, executionStatus);
        }
        return flowMapper.selectPage(query, offset, limit, executionStatus);
    }

    @Override
    public int countFlows(BrickFlow query, Integer endpointId, Integer grpcEndpointId, String executionStatus) {
        return flowMapper.count(query, executionStatus);
    }

    @Override
    @Transactional
    public int createFlow(BrickFlow flow, List<BrickFlowNode> nodes, List<BrickFlowEdge> edges, String operator) {
        flow.setIsDeleted(0);
        flow.setCreateBy(operator);
        int result = flowMapper.insert(flow);

        if (nodes != null && !nodes.isEmpty()) {
            for (BrickFlowNode node : nodes) {
                node.setFlowId(flow.getId());
                node.setIsDeleted(0);
                node.setCreateBy(operator);
            }
            nodeMapper.batchInsert(nodes);
        }

        if (edges != null && !edges.isEmpty()) {
            for (BrickFlowEdge edge : edges) {
                edge.setFlowId(flow.getId());
                edge.setIsDeleted(0);
                edge.setCreateBy(operator);
            }
            edgeMapper.batchInsert(edges);
        }

        return result;
    }

    @Override
    @Transactional
    public int createFullFlow(BrickFlow flow, List<BrickFlowFullNode> fullNodes, List<BrickFlowEdge> edges, String operator) {
        flow.setIsDeleted(0);
        flow.setCreateBy(operator);
        int result = flowMapper.insert(flow);

        if (fullNodes != null && !fullNodes.isEmpty()) {
            List<BrickFlowNode> nodes = new ArrayList<>();
            for (BrickFlowFullNode fullNode : fullNodes) {
                BrickFlowNode node = new BrickFlowNode();
                node.setFlowId(flow.getId());
                node.setEndpointId(fullNode.getEndpointId());
                node.setTimeoutSec(fullNode.getTimeoutSec());
                node.setRetries(fullNode.getRetries());
                node.setHeadersJson(fullNode.getHeadersJson());
                node.setPayloadJson(fullNode.getPayloadJson());
                node.setQueryParamsJson(fullNode.getQueryParamsJson());
                node.setPathVarsJson(fullNode.getPathVarsJson());
                node.setNodeType(fullNode.getNodeType());
                node.setGrpcEndpointId(fullNode.getGrpcEndpointId());
                node.setX(fullNode.getX());
                node.setY(fullNode.getY());
                node.setIsDeleted(0);
                node.setCreateBy(operator);
                nodes.add(node);
            }
            nodeMapper.batchInsert(nodes);
        }

        if (edges != null && !edges.isEmpty()) {
            for (BrickFlowEdge edge : edges) {
                edge.setFlowId(flow.getId());
                edge.setIsDeleted(0);
                edge.setCreateBy(operator);
            }
            edgeMapper.batchInsert(edges);
        }

        return result;
    }

    @Override
    @Transactional
    public int updateFlow(BrickFlow flow, List<BrickFlowNode> nodes, List<BrickFlowEdge> edges, String operator) {
        flow.setUpdateBy(operator);
        int result = flowMapper.updateById(flow);

        nodeMapper.deleteByFlowId(flow.getId());
        if (nodes != null && !nodes.isEmpty()) {
            for (BrickFlowNode node : nodes) {
                node.setFlowId(flow.getId());
                node.setIsDeleted(0);
                node.setCreateBy(operator);
            }
            nodeMapper.batchInsert(nodes);
        }

        edgeMapper.deleteByFlowId(flow.getId());
        if (edges != null && !edges.isEmpty()) {
            for (BrickFlowEdge edge : edges) {
                edge.setFlowId(flow.getId());
                edge.setIsDeleted(0);
                edge.setCreateBy(operator);
            }
            edgeMapper.batchInsert(edges);
        }

        return result;
    }

    @Override
    public int deleteFlow(Integer id, String operator) {
        return flowMapper.softDeleteById(id, operator);
    }

    @Override
    public int hardDeleteFlows(List<Integer> ids) {
        return flowMapper.hardDeleteByIds(ids);
    }

    @Override
    public Map<String, Object> getFlowDetail(Integer id) {
        BrickFlow flow = flowMapper.selectById(id);
        List<BrickFlowNode> nodes = nodeMapper.selectByFlowId(id);
        List<BrickFlowEdge> edges = edgeMapper.selectByFlowId(id);

        Map<String, Object> result = new HashMap<>();
        result.put("flow", flow);
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    @Override
    public Map<String, Object> getFullFlowDetail(Integer id) {
        return getFlowDetail(id);
    }

    @Override
    @Transactional
    public int createTemplate(Integer swaggerMappingId, String name, String description, String operator, AppSwaggerMapping swaggerMapping) {
        BrickFlow template = new BrickFlow();
        template.setName(name);
        template.setEnv(swaggerMapping.getEnv());
        template.setSwaggerMappingId(swaggerMappingId);
        template.setAppConfigId(swaggerMapping.getAppConfigId());
        template.setDescription(description);
        template.setStatus("draft");
        template.setVersion(0);
        template.setFlowTemplateId(-1);
        template.setIsDeleted(0);
        template.setSource(0);
        template.setCreateBy(operator);
        return flowMapper.insert(template);
    }

    @Override
    public List<BrickFlow> listTemplates(BrickFlow query, int offset, int limit, Integer endpointId) {
        return flowMapper.selectTemplatesBySwaggerMappingId(
                query.getSwaggerMappingId(), query.getName(), offset, limit);
    }

    @Override
    public int countTemplates(BrickFlow query, Integer endpointId) {
        return flowMapper.countTemplatesBySwaggerMappingId(query.getSwaggerMappingId(), query.getName());
    }

    @Override
    public BrickFlowRun runFlow(Integer id, String operator, String overrideBaseUrl, Integer runType) {
        return runFlow(id, operator, overrideBaseUrl, runType, null, null);
    }

    @Override
    public BrickFlowRun runFlow(Integer id, String operator, String overrideBaseUrl, Integer runType, Map<String, String> customHeaders) {
        return runFlow(id, operator, overrideBaseUrl, runType, customHeaders, null);
    }

    @Override
    public BrickFlowRun runFlow(Integer id, String operator, String overrideBaseUrl, Integer runType,
                                 Map<String, String> customHeaders, Long suiteRunId) {
        BrickFlow flow = flowMapper.selectById(id);
        if (flow == null) {
            throw new RuntimeException("Flow not found: " + id);
        }

        BrickFlowRun run = new BrickFlowRun();
        run.setFlowId(id);
        run.setStatus("running");
        run.setTriggeredBy(operator);
        run.setRunType(runType != null ? runType : 0);
        run.setOverrideBaseUrl(overrideBaseUrl);
        run.setSuiteRunId(suiteRunId);
        run.setStartTime(new Date());
        run.setCreateBy(operator);
        runMapper.insert(run);

        return run;
    }

    @Override
    public BrickFlowRun runFlowWithCustomNodeParams(Integer id, String operator, String overrideBaseUrl, Integer runType,
                                                     Long nodeId, Integer endpointId, Integer grpcEndpointId,
                                                     Map<String, Object> customNodeParams) {
        return runFlow(id, operator, overrideBaseUrl, runType);
    }

    @Override
    public Map<String, Object> getRunDetail(Long runId) {
        BrickFlowRun run = runMapper.selectById(runId);
        List<BrickFlowRunNode> nodes = runNodeMapper.selectByRunId(runId);

        Map<String, Object> result = new HashMap<>();
        result.put("run", run);
        result.put("nodes", nodes);
        return result;
    }

    @Override
    public List<Map<String, Object>> listRunsBySwaggerMappingId(Integer swaggerMappingId, String flowName,
                                                                   String flowNameLike, String status,
                                                                   String startDate, String endDate,
                                                                   Long testSuiteRunId, String batchId,
                                                                   Integer runType, Boolean distinctLatest,
                                                                   int offset, int limit) {
        return runMapper.selectRunsBySwaggerMappingId(
                swaggerMappingId, flowName, flowNameLike, status, startDate, endDate,
                testSuiteRunId, batchId, runType, distinctLatest, offset, limit);
    }

    @Override
    public int countRunsBySwaggerMappingId(Integer swaggerMappingId, String flowName, String flowNameLike,
                                            String status, String startDate, String endDate,
                                            Long testSuiteRunId, String batchId, Integer runType,
                                            Boolean distinctLatest) {
        return runMapper.countRunsBySwaggerMappingId(
                swaggerMappingId, flowName, flowNameLike, status, startDate, endDate,
                testSuiteRunId, batchId, runType, distinctLatest);
    }

    @Override
    @Transactional
    public int copyFlow(Integer sourceFlowId, String newName, String targetEnv, String operator) {
        return copyFlow(sourceFlowId, newName, targetEnv, operator, null);
    }

    @Override
    @Transactional
    public int copyFlow(Integer sourceFlowId, String newName, String targetEnv, String operator, Integer targetSwaggerMappingId) {
        BrickFlow source = flowMapper.selectById(sourceFlowId);
        if (source == null) {
            throw new RuntimeException("Source flow not found: " + sourceFlowId);
        }

        BrickFlow newFlow = new BrickFlow();
        newFlow.setName(newName);
        newFlow.setEnv(targetEnv != null ? targetEnv : source.getEnv());
        newFlow.setSwaggerMappingId(targetSwaggerMappingId != null ? targetSwaggerMappingId : source.getSwaggerMappingId());
        newFlow.setAppConfigId(source.getAppConfigId());
        newFlow.setDescription(source.getDescription());
        newFlow.setStatus(source.getStatus());
        newFlow.setVersion(source.getVersion());
        newFlow.setFlowTemplateId(source.getId());
        newFlow.setSource(source.getSource());
        newFlow.setSharedHeadersJson(source.getSharedHeadersJson());
        newFlow.setIsDeleted(0);
        newFlow.setCreateBy(operator);

        int result = flowMapper.insert(newFlow);

        List<BrickFlowNode> nodes = nodeMapper.selectByFlowId(sourceFlowId);
        if (!nodes.isEmpty()) {
            for (BrickFlowNode node : nodes) {
                node.setFlowId(newFlow.getId());
            }
            nodeMapper.batchInsert(nodes);
        }

        List<BrickFlowEdge> edges = edgeMapper.selectByFlowId(sourceFlowId);
        if (!edges.isEmpty()) {
            for (BrickFlowEdge edge : edges) {
                edge.setFlowId(newFlow.getId());
            }
            edgeMapper.batchInsert(edges);
        }

        return result;
    }

    @Override
    public Map<String, Object> batchCopyFlows(List<apiworkflow.dto.FlowCopyItem> flowCopyItems,
                                               String defaultTargetEnv, String operator) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> successList = new ArrayList<>();
        List<Map<String, Object>> failedList = new ArrayList<>();

        for (apiworkflow.dto.FlowCopyItem item : flowCopyItems) {
            try {
                copyFlow(item.getSourceFlowId(), item.getNewFlowName(), defaultTargetEnv, operator);
                Map<String, Object> successItem = new HashMap<>();
                successItem.put("sourceFlowId", item.getSourceFlowId());
                successItem.put("newFlowName", item.getNewFlowName());
                successList.add(successItem);
            } catch (Exception e) {
                Map<String, Object> failedItem = new HashMap<>();
                failedItem.put("sourceFlowId", item.getSourceFlowId());
                failedItem.put("reason", e.getMessage());
                failedList.add(failedItem);
            }
        }

        result.put("successCount", successList.size());
        result.put("failedCount", failedList.size());
        result.put("successList", successList);
        result.put("failedList", failedList);
        return result;
    }

    @Override
    public String getNodeFullResponse(Long runId, Long nodeId) {
        BrickFlowRunNode node = runNodeMapper.selectByRunIdAndNodeId(runId, nodeId);
        return node != null ? node.getFullResponse() : null;
    }

    @Override
    public BrickFlowRunNode getNodeExecutionDetail(Long runId, Long nodeId) {
        return runNodeMapper.selectByRunIdAndNodeId(runId, nodeId);
    }

    @Override
    public List<BrickFlowRunNodeAssertion> getRunNodeAssertions(Long runNodeId) {
        return new ArrayList<>();
    }
}
