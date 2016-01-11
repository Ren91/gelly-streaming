/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.graph.streaming;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.EdgeDirection;
import org.apache.flink.graph.Vertex;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.TimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.AllWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.RichAllWindowFunction;
import org.apache.flink.streaming.api.windowing.time.AbstractTime;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.api.windowing.windows.Window;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 *
 * Represents a graph stream where the stream consists solely of {@link org.apache.flink.graph.Edge edges}
 * without timestamps.
 * <p>
 * For event-time support @see 
 *
 * @see org.apache.flink.graph.Edge
 *
 * @param <K> the key type for edge and vertex identifiers.
 * @param <EV> the value type for edges.
 */
@SuppressWarnings("serial")
public class SimpleEdgeStream<K, EV> extends GraphStream<K, NullValue, EV> {

	private final StreamExecutionEnvironment context;
	private final DataStream<Edge<K, EV>> edges;

	/**
	 * Creates a graph from an edge stream
	 *
	 * @param edges a DataStream of edges.
	 * @param context the flink execution environment.
	 */
	public SimpleEdgeStream(DataStream<Edge<K, EV>> edges, StreamExecutionEnvironment context) {
		this.edges = edges;
		this.context = context;
	}

	/**
	 * Creates a graph from an edge stream operating in event time specified by timeExtractor 
	 * @param edges
	 * @param timeExtractor
	 * @param context
     */
	public SimpleEdgeStream(DataStream<Edge<K, EV>> edges, TimestampExtractor<Edge<K,EV>> timeExtractor, StreamExecutionEnvironment context) {
		this.edges = edges.assignTimestamps(timeExtractor);
		this.context = context;
	}

	/**
	 * Applies an incremental aggregation on a graphstream and returns a stream of aggregation results 
	 * 
	 * @param graphAggregation
	 * @param <S>
	 * @param <T>
     * @return
     */
	public <S extends Serializable, T> DataStream<T> aggregate(GraphAggregation<K,EV,S,T> graphAggregation) {
		return graphAggregation.run(getEdges());//FIXME
	}
	
	/**
	 * @return the flink streaming execution environment.
	 */
	@Override
	public StreamExecutionEnvironment getContext() {
		return this.context;
	}

	/**
	 * @return the vertex DataStream.
	 */
	@Override
	public DataStream<Vertex<K, NullValue>> getVertices() {
		return this.edges
			.flatMap(new EmitSrcAndTarget<K, EV>())
			.keyBy(0)
			.filter(new FilterDistinctVertices<K>());
	}

	/**
	 * Discretizes the edge stream into tumbling windows of the specified size.
	 * <p>
	 * The edge stream is partitioned so that all neighbors of a vertex belong to the same partition.
	 * The KeyedStream is then windowed into tumbling time windows.
	 * <p>
	 * By default, each vertex is grouped with its outgoing edges.
	 * Use {@link #slice(AbstractTime, EdgeDirection)} to manually set the edge direction grouping.
	 * 
	 * @param size the size of the window
	 * @return a GraphWindowStream of the specified size 
	 */
	public GraphWindowStream<K, EV> slice(AbstractTime size) {
		return slice(size, EdgeDirection.OUT);
	}

	/**
	 * Discretizes the edge stream into tumbling windows of the specified size.
	 * <p>
	 * The edge stream is partitioned so that all neighbors of a vertex belong to the same partition.
	 * The KeyedStream is then windowed into tumbling time windows.
	 * 
	 * @param size the size of the window
	 * @param direction the EdgeDirection to key by
	 * @return a GraphWindowStream of the specified size, keyed by
	 */
	public GraphWindowStream<K, EV> slice(AbstractTime size, EdgeDirection direction)
		throws IllegalArgumentException {

		switch (direction) {
		case IN:
			return new GraphWindowStream<K, EV>(
				this.reverse().getEdges().keyBy(new NeighborKeySelector<K, EV>(0)).timeWindow(size));
		case OUT:
			return new GraphWindowStream<K, EV>(
				getEdges().keyBy(new NeighborKeySelector<K, EV>(0)).timeWindow(size));
		case ALL:
			getEdges().keyBy(0).timeWindow(size);
			return new GraphWindowStream<K, EV>(
				this.undirected().getEdges().keyBy(
					new NeighborKeySelector<K, EV>(0)).timeWindow(size));
		default:
			throw new IllegalArgumentException("Illegal edge direction");
		}
	}

	private static final class NeighborKeySelector<K, EV> implements KeySelector<Edge<K, EV>, K> {
		private final int key;

		public NeighborKeySelector(int k) {
			this.key = k;
		}

		public K getKey(Edge<K, EV> edge) throws Exception {
			return edge.getField(key);
		}
	}

	private static final class EmitSrcAndTarget<K, EV>
			implements FlatMapFunction<Edge<K, EV>, Vertex<K, NullValue>> {
		@Override
		public void flatMap(Edge<K, EV> edge, Collector<Vertex<K, NullValue>> out) throws Exception {
			out.collect(new Vertex<>(edge.getSource(), NullValue.getInstance()));
			out.collect(new Vertex<>(edge.getTarget(), NullValue.getInstance()));
		}
	}

	private static final class FilterDistinctVertices<K>
			implements FilterFunction<Vertex<K, NullValue>> {
		Set<K> keys = new HashSet<>();

		@Override
		public boolean filter(Vertex<K, NullValue> vertex) throws Exception {
			if (!keys.contains(vertex.getId())) {
				keys.add(vertex.getId());
				return true;
			}
			return false;
		}
	}

	/**
	 * @return the edge DataStream.
	 */
	public DataStream<Edge<K, EV>> getEdges() {
		return this.edges;
	}

	/**
	 * Apply a function to the attribute of each edge in the graph stream.
	 *
	 * @param mapper the map function to apply.
	 * @return a new graph stream.
	 */
	public <NV> SimpleEdgeStream<K, NV> mapEdges(final MapFunction<Edge<K, EV>, NV> mapper) {
		TypeInformation<K> keyType = ((TupleTypeInfo<?>) edges.getType()).getTypeAt(0);
		DataStream<Edge<K, NV>> mappedEdges = edges.map(new ApplyMapperToEdgeWithType<>(mapper,
				keyType));
		return new SimpleEdgeStream<>(mappedEdges, this.context);
	}

	private static final class ApplyMapperToEdgeWithType<K, EV, NV>
			implements MapFunction<Edge<K, EV>, Edge<K, NV>>, ResultTypeQueryable<Edge<K, NV>> {
		private MapFunction<Edge<K, EV>, NV> innerMapper;
		private transient TypeInformation<K> keyType;

		public ApplyMapperToEdgeWithType(MapFunction<Edge<K, EV>, NV> theMapper, TypeInformation<K> keyType) {
			this.innerMapper = theMapper;
			this.keyType = keyType;
		}

		public Edge<K, NV> map(Edge<K, EV> edge) throws Exception {
			return new Edge<>(edge.getSource(), edge.getTarget(), innerMapper.map(edge));
		}

		@SuppressWarnings("unchecked")
		@Override
		public TypeInformation<Edge<K, NV>> getProducedType() {
			TypeInformation<NV> valueType = TypeExtractor
					.createTypeInfo(MapFunction.class, innerMapper.getClass(), 1, null, null);

			TypeInformation<?> returnType = new TupleTypeInfo<>(Edge.class, keyType, keyType, valueType);
			return (TypeInformation<Edge<K, NV>>) returnType;
		}
	}

	/**
	 * Apply a filter to each vertex in the graph stream
	 * Since this is an edge-only stream, the vertex filter can only access the key of vertices
	 *
	 * @param filter the filter function to apply.
	 * @return the filtered graph stream.
	 */
	@Override
	public SimpleEdgeStream<K, EV> filterVertices(FilterFunction<Vertex<K, NullValue>> filter) {
		DataStream<Edge<K, EV>> remainingEdges = this.edges
				.filter(new ApplyVertexFilterToEdges<K, EV>(filter));

		return new SimpleEdgeStream<>(remainingEdges, this.context);
	}

	private static final class ApplyVertexFilterToEdges<K, EV>
			implements FilterFunction<Edge<K, EV>> {
		private FilterFunction<Vertex<K, NullValue>> vertexFilter;

		public ApplyVertexFilterToEdges(FilterFunction<Vertex<K, NullValue>> vertexFilter) {
			this.vertexFilter = vertexFilter;
		}

		@Override
		public boolean filter(Edge<K, EV> edge) throws Exception {
			boolean sourceVertexKept = vertexFilter.filter(new Vertex<>(edge.getSource(),
					NullValue.getInstance()));
			boolean targetVertexKept = vertexFilter.filter(new Vertex<>(edge.getTarget(),
					NullValue.getInstance()));

			return sourceVertexKept && targetVertexKept;
		}
	}

	/**
	 * Apply a filter to each edge in the graph stream
	 *
	 * @param filter the filter function to apply.
	 * @return the filtered graph stream.
	 */
	@Override
	public SimpleEdgeStream<K, EV> filterEdges(FilterFunction<Edge<K, EV>> filter) {
		DataStream<Edge<K, EV>> remainingEdges = this.edges.filter(filter);
		return new SimpleEdgeStream<>(remainingEdges, this.context);
	}

	/**
	 * Removes the duplicate edges by storing a neighborhood set for each vertex
	 *
	 * @return a graph stream with no duplicate edges
	 */
	@Override
	public SimpleEdgeStream<K, EV> distinct() {
		DataStream<Edge<K, EV>> edgeStream = this.edges
				.keyBy(0)
				.flatMap(new DistinctEdgeMapper<K, EV>());

		return new SimpleEdgeStream<>(edgeStream, this.getContext());
	}

	private static final class DistinctEdgeMapper<K, EV> implements FlatMapFunction<Edge<K, EV>, Edge<K, EV>> {
		private final Set<K> neighbors;

		public DistinctEdgeMapper() {
			this.neighbors = new HashSet<>();
		}

		@Override
		public void flatMap(Edge<K, EV> edge, Collector<Edge<K, EV>> out) throws Exception {
			if (!neighbors.contains(edge.getTarget())) {
				neighbors.add(edge.getTarget());
				out.collect(edge);
			}
		}
	}

	/**
	 * @return a graph stream with the edge directions reversed
	 */
	public SimpleEdgeStream<K, EV> reverse() {
		return new SimpleEdgeStream<>(this.edges.map(new ReverseEdgeMapper<K, EV>()), this.getContext());
	}

	private static final class ReverseEdgeMapper<K, EV> implements MapFunction<Edge<K, EV>, Edge<K, EV>> {
		@Override
		public Edge<K, EV> map(Edge<K, EV> edge) throws Exception {
			return edge.reverse();
		}
	}

	/**
	 * @param graph the streamed graph to union with
	 * @return a streamed graph where the two edge streams are merged
	 */
	public SimpleEdgeStream<K, EV> union(SimpleEdgeStream<K, EV> graph) {
		return new SimpleEdgeStream<>(this.edges.union(graph.getEdges()), this.getContext());
	}

	/**
	 * @return a graph stream where edges are undirected
	 */
	public SimpleEdgeStream<K, EV> undirected() {
		DataStream<Edge<K, EV>> reversedEdges = this.edges.flatMap(new UndirectEdges<K, EV>());
		return new SimpleEdgeStream<>(reversedEdges, context);
	}

	private static final class UndirectEdges<K, EV>	implements FlatMapFunction<Edge<K,EV>, Edge<K,EV>> {
		@Override
		public void flatMap(Edge<K, EV> edge, Collector<Edge<K, EV>> out) {
			out.collect(edge);
			out.collect(edge.reverse());
		}
	};

	/**
	 * @return a continuously improving data stream representing the number of vertices in the streamed graph
	 */
	public DataStream<Long> numberOfVertices() {
		return this.globalAggregate(new DegreeTypeSeparator<K, EV>(true, true),
				new VertexCountMapper<K>(), true);
	}

	private static final class VertexCountMapper<K> implements FlatMapFunction<Vertex<K, Long>, Long> {
		private Set<K> vertices;

		public VertexCountMapper() {
			this.vertices = new HashSet<>();
		}

		@Override
		public void flatMap(Vertex<K, Long> vertex, Collector<Long> out) throws Exception {
			vertices.add(vertex.getId());
			out.collect((long) vertices.size());
		}
	}

	/**
	 * @return a data stream representing the number of all edges in the streamed graph, including possible duplicates
	 */
	public DataStream<Long> numberOfEdges() {
		return this.edges.map(new TotalEdgeCountMapper<K, EV>()).setParallelism(1);
	}

	private static final class TotalEdgeCountMapper<K, EV> implements MapFunction<Edge<K, EV>, Long> {
		private long edgeCount;

		public TotalEdgeCountMapper() {
			edgeCount = 0;
		}

		@Override
		public Long map(Edge<K, EV> edge) throws Exception {
			edgeCount++;
			return edgeCount;
		}
	}

	/**
	 * Get the degree stream
	 *
	 * @return a stream of vertices, with the degree as the vertex value
	 * @throws Exception
	 */
	@Override
	public DataStream<Vertex<K, Long>> getDegrees() throws Exception {
		return this.aggregate(new DegreeTypeSeparator<K, EV>(true, true),
				new DegreeMapFunction<K>());
	}

	/**
	 * Get the in-degree stream
	 *
	 * @return a stream of vertices, with the in-degree as the vertex value
	 * @throws Exception
	 */
	public DataStream<Vertex<K, Long>> getInDegrees() throws Exception {
		return this.aggregate(new DegreeTypeSeparator<K, EV>(true, false),
				new DegreeMapFunction<K>());
	}

	/**
	 * Get the out-degree stream
	 *
	 * @return a stream of vertices, with the out-degree as the vertex value
	 * @throws Exception
	 */
	public DataStream<Vertex<K, Long>> getOutDegrees() throws Exception {
		return this.aggregate(new DegreeTypeSeparator<K, EV>(false, true),
				new DegreeMapFunction<K>());
	}

	private static final class DegreeTypeSeparator <K, EV>
			implements FlatMapFunction<Edge<K, EV>, Vertex<K, Long>> {
		private final boolean collectIn;
		private final boolean collectOut;

		public DegreeTypeSeparator(boolean collectIn, boolean collectOut) {
			this.collectIn = collectIn;
			this.collectOut = collectOut;
		}

		@Override
		public void flatMap(Edge<K, EV> edge, Collector<Vertex<K, Long>> out) throws Exception {
			if (collectOut) {
				out.collect(new Vertex<>(edge.getSource(), 1L));
			}
			if (collectIn) {
				out.collect(new Vertex<>(edge.getTarget(), 1L));
			}
		}
	}

	private static final class DegreeMapFunction <K>
			implements MapFunction<Vertex<K, Long>, Vertex<K, Long>> {
		private final Map<K, Long> localDegrees;

		public DegreeMapFunction() {
			localDegrees = new HashMap<>();
		}

		@Override
		public Vertex<K, Long> map(Vertex<K, Long> degree) throws Exception {
			K key = degree.getId();
			if (!localDegrees.containsKey(key)) {
				localDegrees.put(key, 0L);
			}
			localDegrees.put(key, localDegrees.get(key) + degree.getValue());
			return new Vertex<>(key, localDegrees.get(key));
		}
	}

	/**
	 * The aggregate function splits the edge stream up into a vertex stream and applies
	 * a mapper on the resulting vertices
	 *
	 * @param edgeMapper the mapper that converts the edge stream to a vertex stream
	 * @param vertexMapper the mapper that aggregates vertex values
	 * @param <VV> the vertex value used
	 * @return a stream of vertices with the aggregated vertex value
	 */
	public <VV> DataStream<Vertex<K, VV>> aggregate(FlatMapFunction<Edge<K, EV>, Vertex<K, VV>> edgeMapper,
			MapFunction<Vertex<K, VV>, Vertex<K, VV>> vertexMapper) {
		return this.edges.flatMap(edgeMapper)
				.keyBy(0)
				.map(vertexMapper);
	}

	/**
	 * Returns a global aggregate on the previously split vertex stream
	 *
	 * @param edgeMapper the mapper that converts the edge stream to a vertex stream
	 * @param vertexMapper the mapper that aggregates vertex values
	 * @param collectUpdates boolean specifying whether the aggregate should only be collected when there is an update
	 * @param <VV> the return value type
	 * @return a stream of the aggregated values
	 */
	public <VV> DataStream<VV> globalAggregate(FlatMapFunction<Edge<K, EV>, Vertex<K, VV>> edgeMapper,
			FlatMapFunction<Vertex<K, VV>, VV> vertexMapper, boolean collectUpdates) {

		DataStream<VV> result = this.edges.flatMap(edgeMapper)
				.setParallelism(1)
				.flatMap(vertexMapper)
				.setParallelism(1);

		if (collectUpdates) {
			result = result.flatMap(new GlobalAggregateMapper<VV>())
					.setParallelism(1);
		}

		return result;
	}

	private static final class GlobalAggregateMapper<VV> implements FlatMapFunction<VV, VV> {
		VV previousValue;

		public GlobalAggregateMapper() {
			previousValue = null;
		}

		@Override
		public void flatMap(VV vv, Collector<VV> out) throws Exception {
			if (!vv.equals(previousValue)) {
				previousValue = vv;
				out.collect(vv);
			}
		}
	}

	/**
	 * Aggregates the results of a distributed mapper in a merge-tree structure
	 *
	 * @param initMapper the map function that transforms edges into the intermediate data type
	 * @param treeMapper the map function that performs the aggregation between the data elements
	 * @param timeMillis the time to buffer a window before the first level
	 * @param <T> the inner data type
	 * @param <U> the window data type
	 * @return an aggregated stream of the given data type
	 */
	public <T, U extends Window> DataStream<T> mergeTree(FlatMapFunction<Edge<K, EV>, T> initMapper,
			MapFunction<T, T> treeMapper, long timeMillis) {
		int dop = this.context.getParallelism();
		int levels = (int) (Math.log(dop) / Math.log(2));

		DataStream<Tuple2<Integer, T>> chainedStream = this.edges
				.flatMap(new MergeTreeWrapperMapper<>(initMapper));

		for (int i = 0; i < levels; ++i) {
			chainedStream = chainedStream.
					timeWindowAll(Time.milliseconds(timeMillis))
					.apply(new MergeTreeWindowMapper<>(treeMapper));

			if (i < levels - 1) {
				chainedStream = chainedStream.keyBy(new MergeTreeKeySelector<T>(i));
			}
		}

		return chainedStream.map(new MergeTreeProjectionMapper<T>());
	}

	private static final class MergeTreeWrapperMapper<K, EV, T>
			extends RichFlatMapFunction<Edge<K, EV>, Tuple2<Integer, T>>
			implements ResultTypeQueryable<Tuple2<Integer, T>> {
		private final FlatMapFunction<Edge<K, EV>, T> initMapper;

		public MergeTreeWrapperMapper(FlatMapFunction<Edge<K, EV>, T> initMapper) {
			this.initMapper = initMapper;
		}

		@Override
		public void flatMap(Edge<K, EV> edge, final Collector<Tuple2<Integer, T>> out) throws Exception {
			initMapper.flatMap(edge, new Collector<T>() {
				@Override
				public void collect(T t) {
					out.collect(new Tuple2<>(getRuntimeContext().getIndexOfThisSubtask(), t));
				}

				@Override
				public void close() {}
			});
		}

		@Override
		public TypeInformation<Tuple2<Integer, T>> getProducedType() {
			TypeInformation<Integer> keyType = TypeExtractor.getForClass(Integer.class);
			TypeInformation<T> innerType = TypeExtractor.createTypeInfo(FlatMapFunction.class,
					initMapper.getClass(), 1, null, null);

			return new TupleTypeInfo<>(keyType, innerType);
		}
	}

	
	private static final class MergeTreeWindowMapper<T>
			extends RichAllWindowFunction<Tuple2<Integer, T>, Tuple2<Integer, T>, TimeWindow> implements AllWindowFunction<Tuple2<Integer, T>, Tuple2<Integer, T>, TimeWindow> {
		private final MapFunction<T, T> treeMapper;

		public MergeTreeWindowMapper(MapFunction<T, T> treeMapper) {
			this.treeMapper = treeMapper;
		}

		@Override
		public void apply(TimeWindow globalWindow, Iterable<Tuple2<Integer, T>> iterable, Collector<Tuple2<Integer, T>> collector) throws Exception {
				T t = null;
				for (Tuple2<Integer, T> entry : iterable) {
					t = treeMapper.map(entry.f1);
				}
				collector.collect(new Tuple2<>((getRuntimeContext().getIndexOfThisSubtask()), t));
			}
	}

		private static final class MergeTreeKeySelector<T>
			implements KeySelector<Tuple2<Integer, T>, Integer> {
		private int level;

		public MergeTreeKeySelector(int level) {
			this.level = level;
		}

		@Override
		public Integer getKey(Tuple2<Integer, T> input) throws Exception {
			return input.f0 >> (level + 1);
		}
	}

	private static final class MergeTreeProjectionMapper<T>
			implements MapFunction<Tuple2<Integer, T>, T> {
		public MergeTreeProjectionMapper() {
		}

		@Override
		public T map(Tuple2<Integer, T> input) throws Exception {
			return input.f1;
		}
	}
}