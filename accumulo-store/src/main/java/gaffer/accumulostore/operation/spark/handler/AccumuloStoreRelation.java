/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gaffer.accumulostore.operation.spark.handler;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import gaffer.accumulostore.AccumuloStore;
import gaffer.data.element.Edge;
import gaffer.data.element.Element;
import gaffer.data.element.ElementComponentKey;
import gaffer.data.element.Entity;
import gaffer.data.elementdefinition.view.View;
import gaffer.data.elementdefinition.view.ViewElementDefinition;
import gaffer.function.FilterFunction;
import gaffer.function.context.ConsumerFunctionContext;
import gaffer.function.simple.filter.Exists;
import gaffer.function.simple.filter.IsEqual;
import gaffer.function.simple.filter.IsIn;
import gaffer.function.simple.filter.IsLessThan;
import gaffer.function.simple.filter.IsMoreThan;
import gaffer.function.simple.filter.Not;
import gaffer.operation.OperationException;
import gaffer.operation.data.EntitySeed;
import gaffer.operation.simple.spark.AbstractGetRDD;
import gaffer.operation.simple.spark.GetRDDOfAllElements;
import gaffer.operation.simple.spark.GetRDDOfElements;
import gaffer.store.schema.SchemaEdgeDefinition;
import gaffer.store.schema.SchemaElementDefinition;
import gaffer.store.schema.SchemaEntityDefinition;
import gaffer.user.User;
import org.apache.commons.lang.ArrayUtils;
import org.apache.spark.rdd.RDD;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.Row$;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.sources.And;
import org.apache.spark.sql.sources.BaseRelation;
import org.apache.spark.sql.sources.EqualNullSafe;
import org.apache.spark.sql.sources.EqualTo;
import org.apache.spark.sql.sources.Filter;
import org.apache.spark.sql.sources.GreaterThan;
import org.apache.spark.sql.sources.GreaterThanOrEqual;
import org.apache.spark.sql.sources.In;
import org.apache.spark.sql.sources.IsNotNull;
import org.apache.spark.sql.sources.IsNull;
import org.apache.spark.sql.sources.LessThan;
import org.apache.spark.sql.sources.LessThanOrEqual;
import org.apache.spark.sql.sources.PrunedFilteredScan;
import org.apache.spark.sql.sources.PrunedScan;
import org.apache.spark.sql.sources.TableScan;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.runtime.AbstractFunction1;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Allows Apache Spark to retrieve data from an {@link AccumuloStore} as a <code>DataFrame</code>. Spark's Java API
 * does not expose the <code>DataFrame</code> class, but it is just a type alias for a {@link
 * org.apache.spark.sql.Dataset} of {@link Row}s. As a <code>DataFrame</code> is required to have a known schema, an
 * <code>AccumuloStoreRelation</code> requires a group to be specified. The schema for that group is used to create
 * the schema for the <code>DataFrame</code>.
 * <p>
 * <code>AccumuloStoreRelation</code> implements the {@link TableScan} interface which allows all {@link Element}s to
 * of the specified group to be returned to the <code>DataFrame</code>.
 * <p>
 * <code>AccumuloStoreRelation</code> implements the {@link PrunedScan} interface which allows all {@link Element}s
 * of the specified group to be returned to the <code>DataFrame</code> but with only the specified columns returned.
 * Currently, {@link AccumuloStore} does not allow projection of properties in the tablet server, so this projection
 * is performed within the Spark executors, rather than in Accumulo's tablet servers. Once {@link AccumuloStore}
 * supports this projection in the tablet servers, then this will become more efficient.
 * <p>
 * <code>AccumuloStoreRelation</code> implements the {@link PrunedFilteredScan} interface which allows only
 * {@link Element}s that match the the provided {@link Filter}s to be returned. The majority of these are implemented
 * by adding them to the {@link View}, which causes them to be applied on Accumulo's tablet server (i.e. before
 * the data is sent to a Spark executor). If a {@link Filter} is specified that specifies either the vertex in an
 * {@link Entity} or either the source or destination vertex in an {@link Edge} then this is applied by using the
 * appropriate range scan on Accumulo. Queries against this <code>DataFrame</code> that do this should be very
 * quick.
 */
public class AccumuloStoreRelation extends BaseRelation implements TableScan, PrunedScan, PrunedFilteredScan {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccumuloStoreRelation.class);

    private enum EntityOrEdge {
        ENTITY, EDGE
    }

    public static final String VERTEX_COL_NAME = "vertex";
    public static final String SRC_COL_NAME = "src";
    public static final String DST_COL_NAME = "dst";

    private SQLContext sqlContext;
    private String group;
    private AccumuloStore store;
    private User user;
    private StructType structType;
    private EntityOrEdge entityOrEdge;
    private LinkedHashSet<String> usedProperties = new LinkedHashSet<>();

    public AccumuloStoreRelation(final SQLContext sqlContext,
                                 final String group,
                                 final AccumuloStore store,
                                 final User user) {
        this.sqlContext = sqlContext;
        this.group = group;
        this.store = store;
        this.user = user;
        buildSchema();
    }

    @Override
    public SQLContext sqlContext() {
        return sqlContext;
    }

    @Override
    public StructType schema() {
        return structType;
    }

    /**
     * Creates a <code>DataFrame</code> of all {@link Element}s from <code>group</code>.
     *
     * @return An {@link RDD} of {@link Row}s containing {@link Element}s whose group is <code>group</code>.
     */
    @Override
    public RDD<Row> buildScan() {
        try {
            LOGGER.info("Building GetRDDOfAllElements with view set to group {}", group);
            final GetRDDOfAllElements operation = new GetRDDOfAllElements(sqlContext.sparkContext());
            View view;
            if (entityOrEdge.equals(EntityOrEdge.ENTITY)) {
                view = new View.Builder().entity(group).build();
            } else {
                view = new View.Builder().edge(group).build();
            }
            operation.setView(view);
            final RDD<Element> rdd = store.execute(operation, user);
            return rdd.map(new ElementToRow(usedProperties), ClassTagConstants.ROW_CLASS_TAG);
        } catch (final OperationException e) {
            LOGGER.error("OperationException while executing operation: {}", e);
            return null;
        }
    }

    /**
     * Creates a <code>DataFrame</code> of all {@link Element}s from <code>group</code> with columns that are not
     * required filtered out.
     * <p>
     * Currently this does not push the projection down to the store (i.e. it should be implemented in an iterator,
     * not in the transform). Issue 320 refers to this.
     *
     * @param requiredColumns The columns to return.
     * @return An {@link RDD} of {@link Row}s containing the requested columns.
     */
    @Override
    public RDD<Row> buildScan(final String[] requiredColumns) {
        try {
            LOGGER.info("Building scan with required columns: {}", ArrayUtils.toString(requiredColumns));
            LOGGER.info("Building GetRDDOfAllElements with view set to group {}", group);
            final GetRDDOfAllElements operation = new GetRDDOfAllElements(sqlContext.sparkContext());
            View view;
            if (entityOrEdge.equals(EntityOrEdge.ENTITY)) {
                view = new View.Builder().entity(group).build();
            } else {
                view = new View.Builder().edge(group).build();
            }
            operation.setView(view);
            final RDD<Element> rdd = store.execute(operation, user);
            return rdd.map(new ElementToRow(new LinkedHashSet<>(Arrays.asList(requiredColumns))), ClassTagConstants.ROW_CLASS_TAG);
        } catch (final OperationException e) {
            LOGGER.error("OperationException while executing operation {}", e);
            return null;
        }
    }

    /**
     * Creates a <code>DataFrame</code> of all {@link Element}s from <code>group</code> with columns that are not
     * required filtered out and with (some of) the supplied {@link Filter}s applied.
     * <p>
     * Note that Spark also applies the provided {@link Filter}s - applying them here is an optimisation to reduce
     * the amount of data transferred from the store to Spark's executors (this is known as "predicate pushdown").
     * <p>
     * Currently this does not push the projection down to the store (i.e. it should be implemented in an iterator,
     * not in the transform). Issue 320 refers to this.
     *
     * @param requiredColumns The columns to return.
     * @param filters         The pre Aggregation {@link Filter}s to apply.
     * @return An {@link RDD} of {@link Row}s containing the requested columns.
     */
    @Override
    public RDD<Row> buildScan(final String[] requiredColumns, final Filter[] filters) {
        LOGGER.info("Building scan with required columns {} and {} filters ({})", ArrayUtils.toString(requiredColumns),
                filters.length, ArrayUtils.toString(filters));
        // If any of the filters can be translated into Accumulo queries (i.e. specifying ranges rather than a full
        // table scan) then do this.
        AbstractGetRDD<?> operation = null;
        for (final Filter filter : filters) {
            if (filter instanceof EqualTo) {
                final EqualTo equalTo = (EqualTo) filter;
                final String attribute = equalTo.attribute();
                if (attribute.equals(SRC_COL_NAME) || attribute.equals(DST_COL_NAME) || attribute.equals(VERTEX_COL_NAME)) {
                    LOGGER.debug("Found EqualTo filter with attribute {}, creating GetRDDOfElements", attribute);
                    operation = new GetRDDOfElements<>(sqlContext.sparkContext(), new EntitySeed(equalTo.value()));
                }
                break;
            }
        }
        if (operation == null) {
            LOGGER.debug("Creating GetRDDOfAllElements");
            operation = new GetRDDOfAllElements(sqlContext.sparkContext());
        }
        // Create view based on filters and add to operation
        final List<ConsumerFunctionContext<ElementComponentKey, FilterFunction>> filterList = new ArrayList<>();
        for (final Filter filter : filters) {
            filterList.addAll(getFunctionsFromFilter(filter));
        }
        final ViewElementDefinition ved = new ViewElementDefinition();
        ved.addPreAggregationElementFilterFunctions(filterList);
        View view;
        if (entityOrEdge.equals(EntityOrEdge.ENTITY)) {
            view = new View.Builder().entity(group, ved).build();
        } else {
            view = new View.Builder().edge(group, ved).build();
        }
        operation.setView(view);
        // Create RDD
        try {
            final RDD<Element> rdd = store.execute(operation, user);
            return rdd.map(new ElementToRow(new LinkedHashSet<>(Arrays.asList(requiredColumns))), ClassTagConstants.ROW_CLASS_TAG);
        } catch (final OperationException e) {
            LOGGER.error("OperationException while executing operation {}", e);
            return null;
        }
    }

    /**
     * Converts a Spark {@link Filter} to a Gaffer {@link ConsumerFunctionContext}.
     * <p>
     * Note that Spark also applies all the filters provided to the {@link #buildScan(String[], Filter[])} method so
     * not implementing some of the provided {@link Filter}s in Gaffer will not cause errors. However, as many as
     * possible should be implemented so that as much filtering as possible happens in iterators running in Accumulo's
     * tablet servers (this avoids unnecessary data transfer from Accumulo to Spark).
     *
     * @param filter The {@link Filter} to transform.
     * @return Gaffer function(s) implementing the provided {@link Filter}.
     */
    private List<ConsumerFunctionContext<ElementComponentKey, FilterFunction>> getFunctionsFromFilter(final Filter filter) {
        final List<ConsumerFunctionContext<ElementComponentKey, FilterFunction>> functions = new ArrayList<>();
        if (filter instanceof EqualTo) {
            // Not dealt with as requires a FilterFunction that returns null if either the controlValue or the
            // test value is null - the API of FilterFunction doesn't permit this.
        } else if (filter instanceof EqualNullSafe) {
            final EqualNullSafe equalNullSafe = (EqualNullSafe) filter;
            final FilterFunction isEqual = new IsEqual(equalNullSafe.value());
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(equalNullSafe.attribute()));
            functions.add(new ConsumerFunctionContext<>(isEqual, ecks));
            LOGGER.debug("Converted {} to IsEqual ({})", filter, ecks.get(0));
        } else if (filter instanceof GreaterThan) {
            final GreaterThan greaterThan = (GreaterThan) filter;
            final FilterFunction isMoreThan = new IsMoreThan((Comparable<?>) greaterThan.value(), false);
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(greaterThan.attribute()));
            functions.add(new ConsumerFunctionContext<>(isMoreThan, ecks));
            LOGGER.debug("Converted {} to isMoreThan ({})", filter, ecks.get(0));
        } else if (filter instanceof GreaterThanOrEqual) {
            final GreaterThanOrEqual greaterThan = (GreaterThanOrEqual) filter;
            final FilterFunction isMoreThan = new IsMoreThan((Comparable<?>) greaterThan.value(), true);
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(greaterThan.attribute()));
            functions.add(new ConsumerFunctionContext<>(isMoreThan, ecks));
            LOGGER.debug("Converted {} to IsMoreThan ({})", filter, ecks.get(0));
        } else if (filter instanceof LessThan) {
            final LessThan lessThan = (LessThan) filter;
            final FilterFunction isLessThan = new IsLessThan((Comparable<?>) lessThan.value(), false);
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(lessThan.attribute()));
            functions.add(new ConsumerFunctionContext<>(isLessThan, ecks));
            LOGGER.debug("Converted {} to IsLessThan ({})", filter, ecks.get(0));
        } else if (filter instanceof LessThanOrEqual) {
            final LessThanOrEqual lessThan = (LessThanOrEqual) filter;
            final FilterFunction isLessThan = new IsLessThan((Comparable<?>) lessThan.value(), true);
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(lessThan.attribute()));
            functions.add(new ConsumerFunctionContext<>(isLessThan, ecks));
            LOGGER.debug("Converted {} to LessThanOrEqual ({})", filter, ecks.get(0));
        } else if (filter instanceof In) {
            final In in = (In) filter;
            final FilterFunction isIn = new IsIn(new HashSet<>(Arrays.asList(in.values())));
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(in.attribute()));
            functions.add(new ConsumerFunctionContext<>(isIn, ecks));
            LOGGER.debug("Converted {} to IsIn ({})", filter, ecks.get(0));
        } else if (filter instanceof IsNull) {
            final IsNull isNull = (IsNull) filter;
            final FilterFunction doesntExist = new Not(new Exists());
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(isNull.attribute()));
            functions.add(new ConsumerFunctionContext<>(doesntExist, ecks));
            LOGGER.debug("Converted {} to Not(Exists) ({})", filter, ecks.get(0));
        } else if (filter instanceof IsNotNull) {
            final IsNotNull isNotNull = (IsNotNull) filter;
            final FilterFunction exists = new Exists();
            final List<ElementComponentKey> ecks = Collections.singletonList(new ElementComponentKey(isNotNull.attribute()));
            functions.add(new ConsumerFunctionContext<>(exists, ecks));
            LOGGER.debug("Converted {} to Exists ({})", filter, ecks.get(0));
        } else if (filter instanceof And) {
            final And and = (And) filter;
            final List<ConsumerFunctionContext<ElementComponentKey, FilterFunction>> left = getFunctionsFromFilter(and.left());
            final List<ConsumerFunctionContext<ElementComponentKey, FilterFunction>> right = getFunctionsFromFilter(and.right());
            functions.addAll(left);
            functions.addAll(right);
            LOGGER.debug("Converted {} to 2 filters ({}, {})", filter, ArrayUtils.toString(left), ArrayUtils.toString(right));
        }
        return functions;
    }

    private void buildSchema() {
        LOGGER.info("Building Spark SQL schema for group {}", group);
        final SchemaElementDefinition elementDefn = store.getSchema().getElement(group);
        final List<StructField> structFieldList = new ArrayList<>();
        if (elementDefn instanceof SchemaEntityDefinition) {
            entityOrEdge = EntityOrEdge.ENTITY;
            final SchemaEntityDefinition entityDefinition = (SchemaEntityDefinition) elementDefn;
            final String vertexClass = store.getSchema().getType(entityDefinition.getVertex()).getClassString();
            final DataType vertexType = getType(vertexClass);
            if (vertexType == null) {
                throw new RuntimeException("Vertex must be a recognised type: found " + vertexClass);
            }
            LOGGER.info("Group {} is an entity group - {} is of type {}", group, VERTEX_COL_NAME, vertexType);
            structFieldList.add(new StructField(VERTEX_COL_NAME, vertexType, false, Metadata.empty()));
            usedProperties.add(VERTEX_COL_NAME);
        } else {
            entityOrEdge = EntityOrEdge.EDGE;
            final SchemaEdgeDefinition edgeDefinition = (SchemaEdgeDefinition) elementDefn;
            final String srcClass = store.getSchema().getType(edgeDefinition.getSource()).getClassString();
            final String dstClass = store.getSchema().getType(edgeDefinition.getDestination()).getClassString();
            final DataType srcType = getType(srcClass);
            final DataType dstType = getType(dstClass);
            if (srcType == null || dstType == null) {
                throw new RuntimeException("Both source and destination must be recognised types: source was "
                        + srcClass + " destination was " + dstClass);
            }
            LOGGER.info("Group {} is an edge group - {} is of type {}, {} is of type {}", group, SRC_COL_NAME, srcType,
                    DST_COL_NAME, dstType);
            structFieldList.add(new StructField(SRC_COL_NAME, srcType, false, Metadata.empty()));
            structFieldList.add(new StructField(DST_COL_NAME, dstType, false, Metadata.empty()));
            usedProperties.add(SRC_COL_NAME);
            usedProperties.add(DST_COL_NAME);
        }
        final Set<String> properties = elementDefn.getProperties();
        for (final String property : properties) {
            final String propertyClass = elementDefn.getPropertyClass(property).getCanonicalName();
            final DataType propertyType = getType(propertyClass);
            if (propertyType == null) {
                LOGGER.warn("Ignoring property {} as not a recognised type", property);
            } else {
                LOGGER.info("Property {} is of type {}", property, propertyType);
                structFieldList.add(new StructField(property, propertyType, false, Metadata.empty()));
                usedProperties.add(property);
            }
        }
        structType = new StructType(structFieldList.toArray(new StructField[structFieldList.size()]));
    }

    /**
     * Converts an {@link Entity} to a {@link Row}, only including properties whose name is in the provided
     * {@link LinkedHashSet}.
     *
     * @param entity     The {@link Entity} to convert.
     * @param properties The properties to be included in the conversion.
     * @return a {@link Row} containing the vertex and the required properties.
     */
    private static Row getRowFromEntity(final Entity entity, final LinkedHashSet<String> properties) {
        final scala.collection.mutable.MutableList<Object> fields = new scala.collection.mutable.MutableList<>();
        for (final String property : properties) {
            switch (property) {
                case VERTEX_COL_NAME:
                    fields.appendElem(entity.getVertex());
                    break;
                default:
                    fields.appendElem(entity.getProperties().get(property));
            }
        }
        return Row$.MODULE$.fromSeq(fields);
    }

    /**
     * Converts an {@link Edge} to a {@link Row}, only including properties whose name is in the provided
     * {@link LinkedHashSet}.
     *
     * @param edge       The {@link Edge} to convert.
     * @param properties The properties to be included in the conversion.
     * @return A {@link Row} containing the source, destination, and the required properties.
     */
    private static Row getRowFromEdge(final Edge edge, final LinkedHashSet<String> properties) {
        final scala.collection.mutable.MutableList<Object> fields = new scala.collection.mutable.MutableList<>();
        for (final String property : properties) {
            switch (property) {
                case SRC_COL_NAME:
                    fields.appendElem(edge.getSource());
                    break;
                case DST_COL_NAME:
                    fields.appendElem(edge.getDestination());
                    break;
                default:
                    fields.appendElem(edge.getProperties().get(property));
            }
        }
        return Row$.MODULE$.fromSeq(fields);
    }

    private static DataType getType(final String className) {
        switch (className) {
            case "java.lang.String":
                return DataTypes.StringType;
            case "java.lang.Integer":
                return DataTypes.IntegerType;
            case "java.lang.Long":
                return DataTypes.LongType;
            case "java.lang.Boolean":
                return DataTypes.BooleanType;
            case "java.lang.Double":
                return DataTypes.DoubleType;
            case "java.lang.Float":
                return DataTypes.FloatType;
            case "java.lang.Byte":
                return DataTypes.ByteType;
            case "java.lang.Short":
                return DataTypes.ShortType;
            default:
                return null;
        }
    }

    static class ElementToRow extends AbstractFunction1<Element, Row> implements Serializable {

        private static final long serialVersionUID = 3090917576150868059L;
        private LinkedHashSet<String> properties;

        ElementToRow(final LinkedHashSet<String> properties) {
            this.properties = properties;
        }

        @SuppressFBWarnings(value = "BC_UNCONFIRMED_CAST", justification = "If not an Entity then must be an Edge")
        @Override
        public Row apply(final Element element) {
            if (element instanceof Entity) {
                return getRowFromEntity((Entity) element, properties);
            }
            return getRowFromEdge((Edge) element, properties);
        }
    }

}
