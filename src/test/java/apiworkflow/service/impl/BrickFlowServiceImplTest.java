package apiworkflow.service.impl;

import apiworkflow.entity.BrickFlow;
import apiworkflow.entity.BrickFlowEdge;
import apiworkflow.entity.BrickFlowNode;
import apiworkflow.mapper.BrickFlowEdgeMapper;
import apiworkflow.mapper.BrickFlowMapper;
import apiworkflow.mapper.BrickFlowNodeMapper;
import apiworkflow.mapper.BrickFlowRunMapper;
import apiworkflow.mapper.BrickFlowRunNodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrickFlowServiceImplTest {

    @Mock
    private BrickFlowMapper flowMapper;

    @Mock
    private BrickFlowNodeMapper nodeMapper;

    @Mock
    private BrickFlowEdgeMapper edgeMapper;

    @Mock
    private BrickFlowRunMapper runMapper;

    @Mock
    private BrickFlowRunNodeMapper runNodeMapper;

    private BrickFlowServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BrickFlowServiceImpl();
        ReflectionTestUtils.setField(service, "flowMapper", flowMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "edgeMapper", edgeMapper);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "runNodeMapper", runNodeMapper);
    }

    @Test
    void createsIndependentNodesForRepeatedEndpointAndMapsTheirEdges() {
        BrickFlow flow = new BrickFlow();
        flow.setName("repeat endpoint");
        when(flowMapper.insert(any(BrickFlow.class))).thenAnswer(invocation -> {
            ((BrickFlow) invocation.getArgument(0)).setId(12);
            return 1;
        });
        assignGeneratedNodeIdsStartingAt(101L);

        List<BrickFlowNode> nodes = Arrays.asList(
                node(-1L, 25),
                node(-2L, 25),
                node(-3L, 25)
        );
        List<BrickFlowEdge> edges = Arrays.asList(
                edge(-1L, -1L, -2L),
                edge(-2L, -2L, -3L)
        );

        service.createFlow(flow, nodes, edges, "graham");

        assertEquals(Long.valueOf(101L), nodes.get(0).getId());
        assertEquals(Long.valueOf(102L), nodes.get(1).getId());
        assertEquals(Long.valueOf(103L), nodes.get(2).getId());
        assertEquals(Integer.valueOf(25), nodes.get(0).getEndpointId());
        assertEquals(Integer.valueOf(25), nodes.get(1).getEndpointId());
        assertEquals(Integer.valueOf(25), nodes.get(2).getEndpointId());

        ArgumentCaptor<List<BrickFlowEdge>> edgesCaptor = edgeListCaptor();
        verify(edgeMapper).batchInsert(edgesCaptor.capture());
        List<BrickFlowEdge> savedEdges = edgesCaptor.getValue();
        assertEquals(Long.valueOf(101L), savedEdges.get(0).getSourceNodeId());
        assertEquals(Long.valueOf(102L), savedEdges.get(0).getTargetNodeId());
        assertEquals(Long.valueOf(102L), savedEdges.get(1).getSourceNodeId());
        assertEquals(Long.valueOf(103L), savedEdges.get(1).getTargetNodeId());
    }

    @Test
    void updateKeepsExistingNodeIdAndMapsNewTemporaryNode() {
        BrickFlow flow = new BrickFlow();
        flow.setId(12);
        when(flowMapper.updateById(flow)).thenReturn(1);

        BrickFlowNode existing = node(101L, 25);
        existing.setFlowId(12);
        when(nodeMapper.selectByFlowId(12)).thenReturn(Collections.singletonList(existing));
        assignGeneratedNodeIdsStartingAt(102L);

        List<BrickFlowNode> nodes = Arrays.asList(node(101L, 25), node(-1L, 25));
        List<BrickFlowEdge> edges = Collections.singletonList(edge(-1L, 101L, -1L));

        service.updateFlow(flow, nodes, edges, "graham");

        verify(nodeMapper).deleteByFlowId(12);
        verify(nodeMapper).updateById(nodes.get(0));
        assertEquals(Long.valueOf(101L), nodes.get(0).getId());
        assertEquals(Long.valueOf(102L), nodes.get(1).getId());
        assertEquals(Integer.valueOf(0), nodes.get(0).getIsDeleted());

        ArgumentCaptor<List<BrickFlowEdge>> edgesCaptor = edgeListCaptor();
        verify(edgeMapper).batchInsert(edgesCaptor.capture());
        BrickFlowEdge savedEdge = edgesCaptor.getValue().get(0);
        assertEquals(Long.valueOf(101L), savedEdge.getSourceNodeId());
        assertEquals(Long.valueOf(102L), savedEdge.getTargetNodeId());
    }

    @Test
    void copyFlowRemapsEdgesToCopiedNodes() {
        BrickFlow source = new BrickFlow();
        source.setId(12);
        source.setName("source");
        when(flowMapper.selectById(12)).thenReturn(source);
        when(flowMapper.insert(any(BrickFlow.class))).thenAnswer(invocation -> {
            ((BrickFlow) invocation.getArgument(0)).setId(13);
            return 1;
        });

        List<BrickFlowNode> sourceNodes = Arrays.asList(node(101L, 25), node(102L, 25));
        when(nodeMapper.selectByFlowId(12)).thenReturn(sourceNodes);
        assignGeneratedNodeIdsStartingAt(201L);

        BrickFlowEdge sourceEdge = edge(301L, 101L, 102L);
        when(edgeMapper.selectByFlowId(12)).thenReturn(Collections.singletonList(sourceEdge));

        service.copyFlow(12, "copy", "test", "graham", null);

        ArgumentCaptor<List<BrickFlowEdge>> edgesCaptor = edgeListCaptor();
        verify(edgeMapper).batchInsert(edgesCaptor.capture());
        BrickFlowEdge copiedEdge = edgesCaptor.getValue().get(0);
        assertEquals(Integer.valueOf(13), copiedEdge.getFlowId());
        assertEquals(Long.valueOf(201L), copiedEdge.getSourceNodeId());
        assertEquals(Long.valueOf(202L), copiedEdge.getTargetNodeId());
    }

    private void assignGeneratedNodeIdsStartingAt(long firstId) {
        AtomicLong sequence = new AtomicLong(firstId);
        when(nodeMapper.insert(any(BrickFlowNode.class))).thenAnswer(invocation -> {
            BrickFlowNode node = invocation.getArgument(0);
            node.setId(sequence.getAndIncrement());
            return 1;
        });
    }

    private BrickFlowNode node(Long id, Integer endpointId) {
        BrickFlowNode node = new BrickFlowNode();
        node.setId(id);
        node.setEndpointId(endpointId);
        node.setNodeType("http");
        return node;
    }

    private BrickFlowEdge edge(Long id, Long sourceNodeId, Long targetNodeId) {
        BrickFlowEdge edge = new BrickFlowEdge();
        edge.setId(id);
        edge.setSourceNodeId(sourceNodeId);
        edge.setTargetNodeId(targetNodeId);
        return edge;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<BrickFlowEdge>> edgeListCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
