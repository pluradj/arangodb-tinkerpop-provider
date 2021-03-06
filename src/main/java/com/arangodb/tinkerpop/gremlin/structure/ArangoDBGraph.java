//////////////////////////////////////////////////////////////////////////////////////////
//
// Implementation of the TinkerPop-Enabled Providers OLTP for ArangoDB
//
// Copyright triAGENS GmbH Cologne and The University of York
//
//////////////////////////////////////////////////////////////////////////////////////////

package com.arangodb.tinkerpop.gremlin.structure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationConverter;
import org.apache.commons.lang3.StringUtils;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.arangodb.ArangoCursor;
import com.arangodb.ArangoGraph;
import com.arangodb.model.GraphCreateOptions;
import com.arangodb.tinkerpop.gremlin.client.ArangoDBGraphClient;
import com.arangodb.tinkerpop.gremlin.client.ArangoDBGraphException;
import com.arangodb.tinkerpop.gremlin.client.ArangoDBIterator;
import com.arangodb.tinkerpop.gremlin.utils.ArangoDBUtil;

/**
 * The ArangoDB graph class.
 *
 * NOTE: USE OF THIS API REQUIRES A USER WITH <b>ADMINISTRATOR</b> ACCESS IF THE <b>DB</b> USED FOR
 * THE GRAPH DOES NOT EXIST. As per ArangoDB, creating DB is only allowed for the root user, hence
 * only the root user can be used if the DB does not exist.
 * <p>
 * <b>ArangoDB and TinkerPop Ids.</b>
 * <p>
 * In TinkerPop, graph elements are expected to have a unique Id within the graph; in ArangoDB the
 * Id (document handle) consists of the collection's name and the document key (_key attribute)
 * separated by /, hence the only way to hint at ids is by providing a _key during construction.
 * Hence, ArangoDBGraph elements do not strictly support <i>User Supplied Ids</i>. We allow
 * ids to be supplied during vertex creation: {@code graph.addVertex(id,x)}, but this id actually
 * represents the _key. As a result, posterior search/match by id must prefix the vertex's label
 * (collection) followed by a /.
 * <p>
 * An ArangoDBGraph is instantiated from an Apache Commons Configuration instance. The configuration
 * must provide both TinkerPop and ArangoDB configuration options. The ArangoDB options are
 * described in the ArangoDB Java Driver <a href="https://github.com/arangodb/arangodb-java-driver/blob/master/docs/Drivers/Java/Reference/Setup.md">documentation.</a>
 *
 * For the TinkerPop part, the configuration must provide as a minimum the database name and the
 * graph name. If no vertex, edge and relation information is provided, the graph will be considered
 * schema-less.
 * <p>
 * All settings are prefixed with "gremlin.arangodb.conf". So, for example, to set the value of the
 * Arango DB hosts property (arango db configuration), the configuration must read:
 * <pre>gremlin.arangodb.conf.arangodb.hosts = 127.0.0.1:8529
 * </pre>
 * while for the db name (graph configuration) it will be:
 * <pre>gremlin.arangodb.conf.graph.db = myDB
 * </pre>
 * <p>
 * To define the schema, (EdgeCollections in ArangoDB world) three properties can be used:
 * <tt>graph.vertex</tt>, <tt>graph.edge</tt> and <tt>graph.relation</tt>. The graph.vertex and
 * graph.edge properties allow definition of the ArangoDB collections used to store nodes and edges
 * respectively. The relations property is used to describe the allowed edge-node relations. For
 * simple graphs, only one graph.vertex and graph.edge properties need to be provided. In this case
 * edges are allowed to connect to any two nodes. For example:
 * <pre>gremlin.arangodb.conf.graph.vertex = Place
 *gremlin.arangodb.conf.graph.edge = Transition
 * </pre>
 * would allow the user to create Vertices that represent Places, and Edges that represent
 * Transitions. A transition can be created between any two Places. If additional vertices and edges
 * were added, the resulting graph schema would be fully connected, that is, edges would be allowed
 * between any two pair of vertices.
 * <p>
 * For more complex graph structures, the graph.relation property is used to tell the ArangoDB what
 * relations are allowed, e.g.:
 * <ul>
 * <li>One-to-one edges
 *<pre>gremlin.arangodb.conf.graph.vertex = Place
 *gremlin.arangodb.conf.graph.vertex = Transition
 *gremlin.arangodb.conf.graph.edge = PTArc
 *gremlin.arangodb.conf.graph.edge = TPArc
 *gremlin.arangodb.conf.graph.relation = PTArc:Place-&gt;Transition
 *gremlin.arangodb.conf.graph.relation = TPArc:Transition-&gt;Place
 *</pre>
 * would allow the user to create nodes to represent Places and Transitions, and edges to represent
 * Arcs. However, in this case, we have two type of arcs: PTArc and TPArc. The relations specify
 * that PTArcs can only go from Place to Transitions and TPArcs can only go from Transitions to
 * Places. A relation can also specify multiple to/from nodes. In this case, the to/from values is a
 * comma separated list of names.
 * <li>Many-to-many edges
 *  <pre>gremlin.arangodb.conf.graph.vertex = male
 *gremlin.arangodb.conf.graph.vertex = female
 *gremlin.arangodb.conf.graph.edge = relation
 *gremlin.arangodb.conf.graph.relation = relation:male,female-&gt;male,female
 *  </pre>
 * </ul>
 * The list of allowed settings is:
 * <ul>
 *   <li>  graph.db: 		The name of the database
 *   <li>  graph.name: 		The name of the graph
 *   <li>  graph.vertex: 	The name of a vertices collection
 *   <li>  graph.edge: 		The name of an edges collection
 *   <li>  graph.relation: 	The allowed from/to relations for edges
 *   <li>  arangodb.hosts
 *   <li>  arangodb.timeout
 *   <li>  arangodb.user
 *   <li>  arangodb.password
 *   <li>  arangodb.usessl
 *   <li>  arangodb.chunksize
 *   <li>  arangodb.connections.max
 *   <li>  arangodb.protocol
 *   <li>  arangodb.acquireHostList
 *   <li>  arangodb.loadBalancingStrategy
 * </ul>
 *
 * @author Achim Brandt (http://www.triagens.de)
 * @author Johannes Gocke (http://www.triagens.de)
 * @author Guido Schwab (http://www.triagens.de)
 * @author Horacio Hoyos Rodriguez (@horaciohoyosr)
 *
 */

@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_INTEGRATE)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_STANDARD)
@Graph.OptIn("com.arangodb.tinkerpop.gremlin.ArangoDBTestSuite")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoCustomTest",
		method = "shouldSupportUUID",
		specific = "graphson-v3",
		reason = "There is a problem with graphson-v3 recreating the edge from a Map.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoEdgeTest",
		method = "shouldReadWriteEdge",
		specific = "graphson-v3",
		reason = "There is a problem with graphson-v3 recreating the edge from a Map.")
// OptOut ALL graph IO out tests. Not possible with ArangoDBedge definitions
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoGraphTest",
		method = "shouldReadWriteModernToFileWithHelpers",
		reason = "Doubles with 0 decimal values are deserialized as Integers: 1.0 == 1. But the test expects a Double.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoGraphTest",
		method = "shouldReadWriteClassic",
		reason = "Doubles with 0 decimal values are deserialized as Integers: 1.0 == 1. But the test expects a Double.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoGraphTest",
		method = "shouldReadWriteModern",
		reason = "Doubles with 0 decimal values are deserialized as Integers: 1.0 == 1. But the test expects a Double.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoGraphTest",
		method = "shouldReadWriteClassicToFileWithHelpers",
		reason = "Doubles with 0 decimal values are deserialized as Integers: 1.0 == 1. But the test expects a Double.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoGraphTest",
		method = "shouldMigrateModernGraph",
		reason = "Doubles with 0 decimal values are deserialized as Integers: 1.0 == 1. But the test expects a Double.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoGraphTest",
		method = "shouldMigrateClassicGraph",
		reason = "Doubles with 0 decimal values are deserialized as Integers: 1.0 == 1. But the test expects a Double.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoVertexTest",
		method = "shouldReadWriteVertexWithBOTHEdges",
		reason = "Doubles with 0 decimal values are deserialized as Integers: 1.0 == 1. But the test expects a Double.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoVertexTest",
		method = "shouldReadWriteVerticesNoEdgesToGraphSONManual",
		reason = "Doubles with 0 decimal values are deserialized as Integers: 1.0 == 1. But the test expects a Double.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoVertexTest",
		method = "shouldReadWriteVerticesNoEdges",
		reason = "Doubles with 0 decimal values are deserialized as Integers: 1.0 == 1. But the test expects a Double.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoPropertyTest",
		method = "shouldReadWriteVertexPropertyWithMetaProperties",
		reason = "Tests expected LoadGraphWith.GraphData.CREW to be loaded, but another graph is, so property navigation fails.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.GraphTest",
		method = "shouldRemoveVertices",
		reason = "Test creates vertices with random labels, which does not work with our schema-based approach.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.GraphTest",
		method = "shouldRemoveEdges",
		reason = "Test creates edges with random labels, which does not work with our schema-based approach.")
/* How to opt-out inner test classes?
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.EdgeTest",
		method = "shouldAutotypeFloatProperties",
		reason = "Arango does not keep strict Number types when serializing/deserializing")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.EdgeTest",
		method = "shouldAutotypeLongProperties",
		reason = "Arango does not keep strict Number types when serializing/deserializing")
Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.IoTest.GraphSONTest",
		method = "shouldReadWriteModernWrappedInJsonObject",
		reason = "Double/Float serialize/deserialize discrepancies.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.GraphSONLegacyTest",
		method = "shouldReadLegacyGraphSON",
		reason = "Double/Float serialize/deserialize discrepancies.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.GraphMLTest",
		method = "shouldReadGraphML",
		reason = "Double/Float serialize/deserialize discrepancies.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.GraphMLTest",
		method = "shouldReadGraphMLWithAllSupportedDataTypes",
		reason = "Double/Float serialize/deserialize discrepancies.")
@Graph.OptOut(
		test = "org.apache.tinkerpop.gremlin.structure.io.GraphMLTest",
		method = "shouldTransformGraphMLV2ToV3ViaXSLT",
		reason = "Double/Float serialize/deserialize discrepancies.")
		*/
public class ArangoDBGraph implements Graph {

	/**
     * The Class ArangoDBGraphFeatures.
     */

	public class ArangoDBGraphFeatures implements Features {

    	/**
         * The Class ArangoDBGraphGraphFeatures.
         */

    	private class ArangoDBGraphGraphFeatures implements GraphFeatures {

			/** The variable features. */
			private VariableFeatures variableFeatures = new ArangoDBGraphVariables.ArangoDBGraphVariableFeatures();

			/**
			 * Instantiates a new ArangoDB graph graph features.
			 */

			ArangoDBGraphGraphFeatures () { }

			@Override
			public boolean supportsComputer() {
				return false;
			}

			@Override
			public boolean supportsThreadedTransactions() {
				return false;
			}

			@Override
			public boolean supportsTransactions() {
				return false;
			}

			@Override
			public VariableFeatures variables() {
				return variableFeatures;
			}
		}

    	/**
         * The Class ArangoDBGraphElementFeatures.
         */

        private class ArangoDBGraphElementFeatures implements ElementFeatures {

            /**
             * Instantiates a new ArangoDB graph element features.
             */

            ArangoDBGraphElementFeatures() { }

            @Override
			public boolean supportsAnyIds() {
				return false;
			}

			@Override
			public boolean supportsCustomIds() {
				return false;
			}

			@Override
			public boolean supportsNumericIds() {
				return false;
			}

			@Override
			public boolean supportsUuidIds() {
				/*	We can not use Java Objects as keys, ergo we can not support UUID and Integer
				 *  the string representation of these is fine for ArangoDB, which makes the test
				 *  complain because it expects the actual class to be deserialized. We can test
				 *  to see if a string is accepted for deserialization.
				 *  TODO As with properties, a way to support this is to store the id value class
				 */
				return false;
			}
        }

		/**
         * The Class ArangoDBGraphVertexFeatures.
         */

        private class ArangoDBGraphVertexFeatures extends ArangoDBGraphElementFeatures implements VertexFeatures {

		    /** The vertex property features. */

    		private final VertexPropertyFeatures vertexPropertyFeatures = new ArangoDBGraphVertexPropertyFeatures();

            /**
             * Instantiates a new ArangoDB graph vertex features.
             */

            ArangoDBGraphVertexFeatures () { }


			@Override
            public VertexPropertyFeatures properties() {
                return vertexPropertyFeatures;
            }
        }

    	/**
         * The Class ArangoDBGraphEdgeFeatures.
         */
        public class ArangoDBGraphEdgeFeatures extends ArangoDBGraphElementFeatures implements EdgeFeatures {

		    /** The edge property features. */

    		private final EdgePropertyFeatures edgePropertyFeatures = new ArangoDBGraphEdgePropertyFeatures();

            /**
             * Instantiates a new ArangoDB graph edge features.
             */

            ArangoDBGraphEdgeFeatures() { }

            @Override
            public EdgePropertyFeatures properties() {
                return edgePropertyFeatures;
            }
        }

        /**
         * The Class ArangoDBGraphVertexPropertyFeatures.
         */

        private class ArangoDBGraphVertexPropertyFeatures implements VertexPropertyFeatures {

		    /**
    		 * Instantiates a new ArangoDB graph vertex property features.
    		 */

    		ArangoDBGraphVertexPropertyFeatures() { }

    		@Override
			public boolean supportsAnyIds() {
				return false;
			}

			@Override
			public boolean supportsCustomIds() {
				return false;
			}

			@Override
			public boolean supportsNumericIds() {
				return false;
			}

			@Override
			public boolean supportsUuidIds() {
				/*	We can not use Java Objects as keys, ergo we can not support UUID and Integer
				 *  the string representation of these is fine for ArangoDB, which makes the test
				 *  complain because it expects the actual class to be deserialized. We can test
				 *  to see if a string is accepted for deserialization.
				 *  TODO As with properties, a way to support this is to store the id value class
				 */
				return false;
			}
        }

        /**
         * The Class ArangoDBGraphEdgePropertyFeatures.
         */
        private class ArangoDBGraphEdgePropertyFeatures implements EdgePropertyFeatures {

		    /**
    		 * Instantiates a new ArangoDB graph edge property features.
    		 */

    		ArangoDBGraphEdgePropertyFeatures() { }
        }

		/** The graph features. */

    	protected GraphFeatures graphFeatures = new ArangoDBGraphGraphFeatures();

        /** The vertex features. */

        protected VertexFeatures vertexFeatures = new ArangoDBGraphVertexFeatures();

        /** The edge features. */

        protected EdgeFeatures edgeFeatures = new ArangoDBGraphEdgeFeatures();

        @Override
		public EdgeFeatures edge() {
			return edgeFeatures;
		}

        @Override
		public GraphFeatures graph() {
			return graphFeatures;
		}

        @Override
		public String toString() {
			return StringFactory.featureString(this);
		}

        @Override
		public VertexFeatures vertex() {
			return vertexFeatures;
		}
    }

	/** The Logger. */

	private static final Logger logger = LoggerFactory.getLogger(ArangoDBGraph.class);

    /** The properties key CONFIG_CONF. */

    public static final String PROPERTY_KEY_PREFIX = "gremlin.arangodb.conf";

    /** The properties key  CONFIG_DB. */

    public static final String PROPERTY_KEY_DB_NAME = "graph.db";

    /** The properties key  CONFIG_NAME. */

    public static final String PROPERTY_KEY_GRAPH_NAME = "graph.name";

    /** The properties key CONFIG_VERTICES. */

    public static final String PROPERTY_KEY_VERTICES = "graph.vertex";

    /** The properties key CONFIG_EDGES. */

    public static final String PROPERTY_KEY_EDGES = "graph.edge";

    /** The properties key CONFIG_RELATIONS. */

    public static final String PROPERTY_KEY_RELATIONS = "graph.relation";

    /** The Constant DEFAULT_VERTEX_COLLECTION. */

    public static final String DEFAULT_VERTEX_COLLECTION = "vertex";

	/** The Constant DEFAULT_VERTEX_COLLECTION. */

    public static final String DEFAULT_EDGE_COLLECTION = "edge";

    /** The regex to match DOCUMENT_KEY. */

    private static final Pattern DOCUMENT_KEY = Pattern.compile("^[A-Za-z0-9_:\\.@()\\+,=;\\$!\\*'%-]*");

	/** The features. */

	private final Features FEATURES = new ArangoDBGraphFeatures();

	/** A ArangoDBGraphClient to handle the connection to the Database. */

	private ArangoDBGraphClient client = null;

	/** The name. */

	private String name;

	/** The vertex collections. */

	private final List<String> vertexCollections;

	/** The edge collections. */

	private final List<String> edgeCollections;

	/** The relations. */

	private final List<String> relations;

	/**  Flat to indicate that the graph has no schema. */

	private boolean schemaless = false;

	/** The configuration. */

	private Configuration configuration;

	/** The variables id. */

	private String variables_id;


    /**
     * Create a new ArangoDBGraph from the provided configuration.
     *
     * @param configuration		the Apache Commons configuration
     * @return 					the Arango DB graph
     */

    public static ArangoDBGraph open(Configuration configuration) {
		return new ArangoDBGraph(configuration);
	}

	/**
	 * Creates a Graph (simple configuration).
	 *
	 * @param configuration 	the Apache Commons configuration
	 */

	public ArangoDBGraph(Configuration configuration) {

		logger.info("Creating new ArangoDB Graph from configuration");
		Configuration arangoConfig = configuration.subset(PROPERTY_KEY_PREFIX);
		vertexCollections = arangoConfig.getList(PROPERTY_KEY_VERTICES).stream()
				.map(String.class::cast)
				.collect(Collectors.toList());
		edgeCollections = arangoConfig.getList(PROPERTY_KEY_EDGES).stream()
				.map(String.class::cast)
				.collect(Collectors.toList());
		relations = arangoConfig.getList(PROPERTY_KEY_RELATIONS).stream()
				.map(String.class::cast)
				.collect(Collectors.toList());
		String graphName = arangoConfig.getString(PROPERTY_KEY_GRAPH_NAME);
		checkValues(arangoConfig.getString(PROPERTY_KEY_DB_NAME), graphName,	vertexCollections,
				edgeCollections, relations);
		if (CollectionUtils.isEmpty(vertexCollections)) {
			schemaless = true;
			vertexCollections.add(DEFAULT_VERTEX_COLLECTION);
		}
		if (CollectionUtils.isEmpty(edgeCollections)) {
			edgeCollections.add(DEFAULT_EDGE_COLLECTION);
		}
		Properties arangoProperties = ConfigurationConverter.getProperties(arangoConfig);
		int batchSize = 0;
		client = new ArangoDBGraphClient(arangoProperties, arangoConfig.getString(PROPERTY_KEY_DB_NAME), batchSize);
        ArangoGraph graph = client.getGraph(graphName);
        GraphCreateOptions options = new  GraphCreateOptions();
        options.orphanCollections(ArangoDBUtil.getCollectioName(graphName, ArangoDBUtil.GRAPH_VARIABLES_COLLECTION));
        if (graph.exists()) {
    		this.name = graph.name();
            ArangoDBUtil.checkGraphForErrors(vertexCollections, edgeCollections, relations, graph, options);
            ArangoCursor<String> iter = client.getGraphVariablesId(this.name);
            if (iter.hasNext()) {
            	this.variables_id = iter.next();
            }
            else {
            	throw new ArangoDBGraphException("Existing graph does not have a Variables collection");
            }
        }
        else {
        	graph = client.createGraph(graphName, vertexCollections,
            		edgeCollections, relations, options);
        	this.name = graph.name();
			ArangoDBGraphVariables variables = new ArangoDBGraphVariables(this, ArangoDBUtil.GRAPH_VARIABLES_COLLECTION);
			client.insertDocument(variables);
			this.variables_id = variables._id();
		}
		this.configuration = configuration;
	}

    @Override
	public Vertex addVertex(Object... keyValues) {
        ElementHelper.legalPropertyKeyValueArray(keyValues);
        Object id;
        String collection;
        if (!schemaless) {
        	collection = ElementHelper.getLabelValue(keyValues).orElse(null);
        	ElementHelper.validateLabel(collection);
        }
        else {
        	collection = DEFAULT_VERTEX_COLLECTION;
        }
        if (!vertexCollections().contains(collection)) {
			throw new IllegalArgumentException(String.format("Vertex label (%s) not in graph (%s) vertex collections.", collection, name));
		}
        ArangoDBVertex vertex = null;
        if (ElementHelper.getIdValue(keyValues).isPresent()) {
        	id = ElementHelper.getIdValue(keyValues).get();
        	if (this.features().vertex().willAllowId(id)) {
        		Matcher m = DOCUMENT_KEY.matcher((String)id);
        		if (m.matches()) {
        			vertex = new ArangoDBVertex(this, collection, id.toString());
        		}
        		else {
            		throw new ArangoDBGraphException(String.format("Given id (%s) has unsupported characters.", id));
            	}
        	}
        	else {
        		throw Vertex.Exceptions.userSuppliedIdsOfThisTypeNotSupported();
        	}

        }
        else {
        	vertex = new ArangoDBVertex(this, collection);
        }
        // The vertex needs to exist before we can attach properties
        client.insertDocument(vertex);
        ElementHelper.attachProperties(vertex, keyValues);
        return vertex;
	}

	/**
	 * Check that the configuration values are sound.
	 *
	 * @param db the db
	 * @param name the name
	 * @param vertices the vertices
	 * @param edges the edges
	 * @param relations the relations
	 */

	private void checkValues(
	    String db,
        String name,
        List<String> vertices,
        List<String> edges,
        List<String> relations) {

		if (StringUtils.isBlank(db)) {
            throw new ArangoDBGraphException(String.format("The provided argument can not be empty/null: %s", db));
		}
		if (StringUtils.isBlank(name)) {
            throw new ArangoDBGraphException(String.format("The provided argument can not be empty/null: %s", name));
		}
		if (CollectionUtils.isEmpty(edges)) {
			logger.warn("Empty edges collection(s), the default 'edge' collection will be used.");
		}
		if ((vertices.size() > 1) && (edges.size() > 1) && CollectionUtils.isEmpty(relations)) {
			throw new ArangoDBGraphException("If more than one vertex/edge collection is provided, relations must be defined");
		}
	}

	@Override
	public void close() {
		client.shutdown();
	}


	@Override
	public GraphComputer compute() throws IllegalArgumentException {
        throw Graph.Exceptions.graphComputerNotSupported();
	}

	@Override
	public <C extends GraphComputer> C compute(Class<C> graphComputerClass) throws IllegalArgumentException {
		throw new UnsupportedOperationException();
	}

	@Override
	public Configuration configuration() {
		return configuration;
	}

	/**
	 * Edge collections.
	 *
	 * @return the list
	 */

	public List<String> edgeCollections() {
		return Collections.unmodifiableList(edgeCollections);
	}

	@Override
	public Iterator<Edge> edges(Object... edgeIds) {
		List<String> edgeCollections = new ArrayList<>();
    	List<String> ids = Arrays.stream(edgeIds)
        		.map(id -> {
        			if (id instanceof Element) {
        				edgeCollections.add(((Element)id).label());
        				return ((Element)id).id();
        			}
        			else {
        				// We only support String ids
        				return id;
        			}
        			})
        		.map(id -> id == null ? (String)id : id.toString())
        		.collect(Collectors.toList());
		return new ArangoDBIterator<Edge>(this, getClient().getGraphEdges(this, ids, edgeCollections));
	}

	@Override
	public Features features() {
		return FEATURES;
	}

	/**
	 * Returns the ArangoDBGraphClient object.
	 *
	 * @return the ArangoDBGraphClient object
	 */

	public ArangoDBGraphClient getClient() {
		return client;
	}

	/**
	 * Returns the identifier of the graph.
	 *
	 * @return the identifier of the graph
	 */

	public String getId() {
		ArangoGraph graph = client.getGraph(name);
		return graph.getInfo().getName();
	}

	/**
	 * The graph name
	 *
	 * @return the name
	 */

	public String name() {
		return this.name;
	}

	@Override
	public Transaction tx() {
		throw Graph.Exceptions.transactionsNotSupported();
	}

	@Override
	public Variables variables() {
		ArangoCursor<ArangoDBGraphVariables> iter = client.getGraphVariables(this.name, variables_id);
		if (iter.hasNext()) {
			ArangoDBGraphVariables v = iter.next();
			v.graph(this);
			return v;
        }
        else {
        	throw new ArangoDBGraphException("Existing graph does not have a Variables collection");
        }
	}

	/**
	 * Vertex collections.
	 *
	 * @return the list
	 */
	public List<String> vertexCollections() {
		return Collections.unmodifiableList(vertexCollections);
	}

	@Override
	public Iterator<Vertex> vertices(Object... vertexIds) {
    	List<String> vertexCollections = new ArrayList<>();
    	List<String> ids = Arrays.stream(vertexIds)
        		.map(id -> {
        			if (id instanceof Vertex) {
        				vertexCollections.add(((Vertex)id).label());
        				return ((Vertex)id).id();
        			}
        			else {
        				// We only support String ids
        				return id;
        			}
        			})
        		.map(id -> id == null ? (String)id : id.toString())
        		.collect(Collectors.toList());
		return new ArangoDBIterator<Vertex>(this, getClient().getGraphVertices(this, ids, vertexCollections));
	}

	@Override
	public String toString() {
		String vertices = vertexCollections().stream()
				.map(vc -> String.format("\"%s\"", vc))
				.collect(Collectors.joining(", ", "{", "}"));
		String edges = edgeCollections().stream()
				.map(vc -> String.format("\"%s\"", vc))
				.collect(Collectors.joining(", ", "{", "}"));
		String relations = relations().stream()
				.map(vc -> String.format("\"%s\"", vc))
				.collect(Collectors.joining(", ", "{", "}"));
		String internal =  "{"
					+ "\"name\":\"" + name() + "\","
					+ "\"vertices\":" + vertices + ","
					+ "\"edges\":" + edges+ ","
					+ "\"relations\":" + relations
				+"}";
		return StringFactory.graphString(this, internal);
	}

	/**
	 * The graph relations.
	 *
	 * @return the collection of relations
	 */
	private Collection<String> relations() {
		return relations;
	}

   // TODO Decide which of these methods we want to keep

//	@Override
//	public <T extends Element> void dropKeyIndex(String key, Class<T> elementClass) {
//
//		List<ArangoDBIndex> indices = null;
//		try {
//			if (elementClass.isAssignableFrom(Vertex.class)) {
//				indices = client.getVertexIndices(simpleGraph);
//			} else if (elementClass.isAssignableFrom(Edge.class)) {
//				indices = client.getEdgeIndices(simpleGraph);
//			}
//		} catch (ArangoDBException e) {
//			logger.warn("error while reading an index", e);
//		}
//
//		String normalizedKey = ArangoDBUtil.normalizeKey(key);
//
//		if (indices != null) {
//			for (ArangoDBIndex index : indices) {
//				if (index.getFields().size() == 1) {
//					deleteIndexByKey(normalizedKey, index);
//				}
//			}
//		}
//
//	}
//
//	private void deleteIndexByKey(String normalizedKey, ArangoDBIndex index) {
//		String field = index.getFields().get(0);
//
//		if (field.equals(normalizedKey)) {
//			try {
//				client.deleteIndex(index.getId());
//			} catch (ArangoDBException e) {
//				logger.warn("error while deleting an index", e);
//			}
//		}
//	}
//
//	@SuppressWarnings("rawtypes")
//	@Override
//	public <T extends Element> void createKeyIndex(String key, Class<T> elementClass, Parameter... indexParameters) {
//
//		IndexType type = IndexType.SKIPLIST;
//		boolean unique = false;
//		List<String> fields = new ArrayList<String>();
//
//		String n = ArangoDBUtil.normalizeKey(key);
//		fields.add(n);
//
//		for (Parameter p : indexParameters) {
//			if ("type".equals(p.getKey())) {
//				type = object2IndexType(p.getValue());
//			}
//			if ("unique".equals(p.getKey())) {
//				unique = (Boolean) p.getValue();
//			}
//		}
//
//		try {
//			if (elementClass.isAssignableFrom(Vertex.class)) {
//				getClient().createVertexIndex(simpleGraph, type, unique, fields);
//			} else if (elementClass.isAssignableFrom(Edge.class)) {
//				getClient().createEdgeIndex(simpleGraph, type, unique, fields);
//			}
//		} catch (ArangoDBException e) {
//			logger.warn("error while creating a vertex index", e);
//		}
//	}

//	private IndexType object2IndexType(Object obj) {
//		if (obj instanceof IndexType) {
//			return (IndexType) obj;
//		}
//
//		if (obj != null) {
//			String str = obj.toString();
//			for (IndexType indexType : IndexType.values()) {
//				if (indexType.toString().equalsIgnoreCase(str)) {
//					return indexType;
//				}
//			}
//		}
//
//		return IndexType.SKIPLIST;
//	}

//	@Override
//	public <T extends Element> Set<String> getIndexedKeys(Class<T> elementClass) {
//		HashSet<String> result = new HashSet<String>();
//		List<ArangoDBIndex> indices = null;
//		try {
//			if (elementClass.isAssignableFrom(Vertex.class)) {
//				indices = client.getVertexIndices(simpleGraph);
//			} else if (elementClass.isAssignableFrom(Edge.class)) {
//				indices = client.getEdgeIndices(simpleGraph);
//			}
//
//			for (ArangoDBIndex i : indices) {
//				if (i.getFields().size() == 1) {
//					addNotSystemKey(result, i);
//				}
//			}
//
//		} catch (ArangoDBException e) {
//			logger.warn("error while reading index keys", e);
//		}
//
//		return result;
//	}

}
