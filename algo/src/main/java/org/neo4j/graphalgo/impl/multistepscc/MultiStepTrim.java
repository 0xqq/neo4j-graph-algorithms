package org.neo4j.graphalgo.impl.multistepscc;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntScatterSet;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntCursor;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphdb.Direction;

import java.util.Iterator;

/**
 * MultiStep SCC trimming algorithm. Removes trivial non-strongly connected
 * components. Its result is a set of nodes without trivial weakly connected nodes
 * as well as dangling nodes without relationships to others.
 *
 * In simple mode only one iteration over all nodeIds is made. During the computation
 * all nodes with either zero incoming or outgoing connections are removed because such
 * nodes can never build a SCC.
 *
 * Using complete mode the logic tries to reduce all nodes until no more changes can be
 * made. It also removes self referencing nodes (which build an SCC only with itself).
 *
 * MultistepSCC basically uses simple-trimming but there may be circumstances where
 * complete trimming results in a better overall performance.
 *
 * Since we cannot alter the Graph I decided to build the reduction using auxiliary degree-arrays.
 * Once initialized with all degrees I do update only it's in- and out- degrees to determine
 * if a node got decoupled in the previous iteration.
 *
 * @author mknblch
 */
public class MultiStepTrim {

    // the graph iface
    private final Graph graph;
    // overall node count
    private final int nodeCount;
    // initial node set
    private final IntSet nodes;

    // auxiliary arrays for nodeCounts
    private final int[] inDegree;
    private final int[] outDegree;

    public MultiStepTrim(Graph graph) {
        this.graph = graph;
        nodeCount = graph.nodeCount();
        nodes = new IntHashSet();
        inDegree = new int[nodeCount];
        outDegree = new int[nodeCount];
    }

    /**
     * compute the resulting nodeSet after trimming
     * @param complete determine if complete or simple trimming should be made
     * @return set of nodes without trivial weakly connected components
     */
    public IntSet compute(boolean complete) {
        reset();
        trim(complete);
        return nodes;
    }

    /**
     * reset auxiliary degree arrays
     */
    private void reset() {
        for (int i = nodeCount - 1; i >= 0; i--) {
            nodes.add(i);
            inDegree[i] = graph.degree(i, Direction.INCOMING);
            outDegree[i] = graph.degree(i, Direction.OUTGOING);
        }
    }

    /**
     * trim the graph by removing nodes with IN- or OUT-Degree of 0
     * and all self-referencing nodes without relationships to others.
     *
     * @param complete true: prune the graph until no more changes can be made
     *                 does only one iteration otherwise
     */
    private void trim(boolean complete) {
        final IntScatterSet remove = new IntScatterSet();
        boolean changes; // tells whether the last iteration changed the graph
        final boolean[] filter = {false};
        do {
            changes = false;
            for(Iterator<IntCursor> it = nodes.iterator(); it.hasNext(); ) {
                final int node = it.next().value;
                filter[0] = false;
                // rm nodes without incoming arcs and update target degrees
                if (inDegree[node] == 0) {
                    graph.forEachRelationship(node, Direction.OUTGOING, (sourceNodeId, targetNodeId, relationId) -> {
                        outDegree[targetNodeId]--;
                        return true;
                    });
                    filter[0] = true;
                }
                // rm nodes without outgoing arcs and update target degrees
                if (outDegree[node] == 0) {
                    graph.forEachRelationship(node, Direction.INCOMING, (sourceNodeId, targetNodeId, relationId) -> {
                        inDegree[targetNodeId]--;
                        return true;
                    });
                    filter[0] = true;
                }
                // rm self loops (only in complete mode)
                if (complete && inDegree[node] == 1 && outDegree[node] == 1) {
                    graph.forEachRelationship(node, Direction.OUTGOING, (sourceNodeId, targetNodeId, relationId) -> {
                        if (sourceNodeId == targetNodeId) {
                            filter[0] = true;
                            return false;
                        }
                        return false;
                    });
                }
                // remove
                if (filter[0]) {
                    remove.add(node);
                }
                changes |= filter[0];
            }
            // unfortunately the iterator does not support removing
            nodes.removeAll(remove);
        } while (changes && complete);
    }
}
