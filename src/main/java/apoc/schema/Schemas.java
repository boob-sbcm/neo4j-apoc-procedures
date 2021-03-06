package apoc.schema;

import apoc.result.AssertSchemaResult;
import apoc.result.ConstraintRelationshipInfo;
import apoc.result.IndexConstraintNodeInfo;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.schema.ConstraintDefinition;
import org.neo4j.graphdb.schema.ConstraintType;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.internal.kernel.api.*;
import org.neo4j.internal.kernel.api.schema.constraints.ConstraintDescriptor;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.SilentTokenNameLookup;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.procedure.*;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.neo4j.graphdb.Label.label;

public class Schemas {
    @Context
    public GraphDatabaseService db;

    @Context
    public KernelTransaction tx;

    @Procedure(value = "apoc.schema.assert", mode = Mode.SCHEMA)
    @Description("apoc.schema.assert({indexLabel:[[indexKeys]], ...}, {constraintLabel:[constraintKeys], ...}, dropExisting : true) yield label, key, keys, unique, action - drops all other existing indexes and constraints when `dropExisting` is `true` (default is `true`), and asserts that at the end of the operation the given indexes and unique constraints are there, each label:key pair is considered one constraint/label. Non-constraint indexes can define compound indexes with label:[key1,key2...] pairings.")
    public Stream<AssertSchemaResult> schemaAssert(@Name("indexes") Map<String, List<Object>> indexes, @Name("constraints") Map<String, List<Object>> constraints, @Name(value = "dropExisting", defaultValue = "true") boolean dropExisting) throws ExecutionException, InterruptedException {
        return Stream.concat(
                assertIndexes(indexes, dropExisting).stream(),
                assertConstraints(constraints, dropExisting).stream());
    }

    @Procedure(value = "apoc.schema.nodes", mode = Mode.SCHEMA)
    @Description("CALL apoc.schema.nodes() yield name, label, properties, status, type")
    public Stream<IndexConstraintNodeInfo> nodes() throws IndexNotFoundKernelException {
        return indexesAndConstraintsForNode();
    }

    @Procedure(value = "apoc.schema.relationships", mode = Mode.SCHEMA)
    @Description("CALL apoc.schema.relationships() yield name, startLabel, type, endLabel, properties, status")
    public Stream<ConstraintRelationshipInfo> relationships() {
        return constraintsForRelationship();
    }

    @UserFunction(value = "apoc.schema.node.indexExists")
    @Description("RETURN apoc.schema.node.indexExists(labelName, propertyNames)")
    public Boolean indexExistsOnNode(@Name("labelName") String labelName, @Name("propertyName") List<String> propertyNames) {
        return indexExists(labelName, propertyNames);
    }

    @UserFunction(value = "apoc.schema.node.constraintExists")
    @Description("RETURN apoc.schema.node.constraintExists(labelName, propertyNames)")
    public Boolean constraintExistsOnNode(@Name("labelName") String labelName, @Name("propertyName") List<String> propertyNames) {
        return constraintsExists(labelName, propertyNames);
    }

    @UserFunction(value = "apoc.schema.relationship.constraintExists")
    @Description("RETURN apoc.schema.relationship.constraintExists(type, propertyNames)")
    public Boolean constraintExistsOnRelationship(@Name("type") String type, @Name("propertyName") List<String> propertyNames) {
        return constraintsExistsForRelationship(type, propertyNames);
    }

    public List<AssertSchemaResult> assertConstraints(Map<String, List<Object>> constraints0, boolean dropExisting) throws ExecutionException, InterruptedException {
        Map<String, List<Object>> constraints = copyMapOfObjects(constraints0);
        List<AssertSchemaResult> result = new ArrayList<>(constraints.size());
        Schema schema = db.schema();

        for (ConstraintDefinition definition : schema.getConstraints()) {
            String label = definition.isConstraintType(ConstraintType.RELATIONSHIP_PROPERTY_EXISTENCE) ? definition.getRelationshipType().name() : definition.getLabel().name();
            AssertSchemaResult info = new AssertSchemaResult(label, Iterables.asList(definition.getPropertyKeys())).unique();
            if (!constraints.containsKey(label) || !constraints.get(label).remove(info.key)) {
                if (dropExisting) {
                    definition.drop();
                    info.dropped();
                }
            }
            result.add(info);
        }

        for (Map.Entry<String, List<Object>> constraint : constraints.entrySet()) {
            for (Object key : constraint.getValue()) {
                if (key instanceof String) {
                    result.add(createUniqueConstraint(schema, constraint.getKey(), key.toString()));
                } else if (key instanceof List) {
                    result.add(createNodeKeyConstraint(constraint.getKey(), (List<Object>) key));
                }
            }
        }
        return result;
    }

    private AssertSchemaResult createNodeKeyConstraint(String lbl, List<Object> keys) {
        String keyProperties = keys.stream()
                .map( property -> String.format("n.`%s`", property))
                .collect( Collectors.joining( "," ) );
        db.execute(String.format("CREATE CONSTRAINT ON (n:`%s`) ASSERT (%s) IS NODE KEY", lbl, keyProperties)).close();
        List<String> keysToSting = keys.stream().map(Object::toString).collect(Collectors.toList());
        return new AssertSchemaResult(lbl, keysToSting).unique().created();
    }

    private AssertSchemaResult createUniqueConstraint(Schema schema, String lbl, String key) {
        schema.constraintFor(label(lbl)).assertPropertyIsUnique(key).create();
        return new AssertSchemaResult(lbl, key).unique().created();
    }

    public List<AssertSchemaResult> assertIndexes(Map<String, List<Object>> indexes0, boolean dropExisting) throws ExecutionException, InterruptedException, IllegalArgumentException {
        Schema schema = db.schema();
        Map<String, List<Object>> indexes = copyMapOfObjects(indexes0);
        List<AssertSchemaResult> result = new ArrayList<>(indexes.size());

        for (IndexDefinition definition : schema.getIndexes()) {
            if (definition.isConstraintIndex())
                continue;

            String label = definition.getLabel().name();
            List<String> keys = new ArrayList<>();
            definition.getPropertyKeys().forEach(keys::add);

            AssertSchemaResult info = new AssertSchemaResult(label, keys);
            if(indexes.containsKey(label)) {
                if (keys.size() > 1) {
                    indexes.get(label).remove(keys);
                } else if (keys.size() == 1) {
                    indexes.get(label).remove(keys.get(0));
                } else
                    throw new IllegalArgumentException("Label given with no keys.");
            }

            if (dropExisting) {
                definition.drop();
                info.dropped();
            }

            result.add(info);
        }

        if (dropExisting)
            indexes = copyMapOfObjects(indexes0);

        for (Map.Entry<String, List<Object>> index : indexes.entrySet()) {
            for (Object key : index.getValue()) {
                if (key instanceof String) {
                    result.add(createSinglePropertyIndex(schema, index.getKey(), (String) key));
                } else if (key instanceof List) {
                    result.add(createCompoundIndex(index.getKey(), (List<String>) key));
                }
            }
        }
        return result;
    }

    private AssertSchemaResult createSinglePropertyIndex(Schema schema, String lbl, String key) {
        schema.indexFor(label(lbl)).on(key).create();
        return new AssertSchemaResult(lbl, key).created();
    }

    private AssertSchemaResult createCompoundIndex(String label, List<String> keys) {
        List<String> backTickedKeys = new ArrayList<>();
        keys.forEach(key->backTickedKeys.add(String.format("`%s`", key)));

        db.execute(String.format("CREATE INDEX ON :`%s` (%s)", label, String.join(",", backTickedKeys)));
        return new AssertSchemaResult(label, keys).created();
    }

    private Map<String, List<Object>> copyMapOfObjects(Map<String, List<Object>> input) {
        if (input == null) {
            return Collections.emptyMap();
        }

        HashMap<String, List<Object>> result = new HashMap<>(input.size());

        input.forEach((k, v) -> result.put(k, new ArrayList<>(v)));
        return result;
    }


    private Map<String, List<String>> copy(Map<String, List<String>> input) {
        if (input == null) {
            return Collections.emptyMap();
        }

        HashMap<String, List<String>> result = new HashMap<>(input.size());

        input.forEach((k, v) -> result.put(k, new ArrayList<>(v)));
        return result;
    }

    /**
     * Checks if an index exists for a given label and a list of properties
     * This method checks for index on nodes
     *
     * @param labelName
     * @param propertyNames
     * @return true if the index exists otherwise it returns false
     */
    private Boolean indexExists(String labelName, List<String> propertyNames) {
        Schema schema = db.schema();

        for (IndexDefinition indexDefinition : Iterables.asList(schema.getIndexes(Label.label(labelName)))) {
            List<String> properties = Iterables.asList(indexDefinition.getPropertyKeys());

            if (properties.equals(propertyNames)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if a constraint exists for a given label and a list of properties
     * This method checks for constraints on node
     *
     * @param labelName
     * @param propertyNames
     * @return true if the constraint exists otherwise it returns false
     */
    private Boolean constraintsExists(String labelName, List<String> propertyNames) {
        Schema schema = db.schema();

        for (ConstraintDefinition constraintDefinition : Iterables.asList(schema.getConstraints(Label.label(labelName)))) {
            List<String> properties = Iterables.asList(constraintDefinition.getPropertyKeys());

            if (properties.equals(propertyNames)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if a constraint exists for a given type and a list of properties
     * This method checks for constraints on relationships
     *
     * @param type
     * @param propertyNames
     * @return true if the constraint exists otherwise it returns false
     */
    private Boolean constraintsExistsForRelationship(String type, List<String> propertyNames) {
        Schema schema = db.schema();

        for (ConstraintDefinition constraintDefinition : Iterables.asList(schema.getConstraints(RelationshipType.withName(type)))) {
            List<String> properties = Iterables.asList(constraintDefinition.getPropertyKeys());

            if (properties.equals(propertyNames)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Collects indexes and constraints for nodes
     *
     * @return
     */
    private Stream<IndexConstraintNodeInfo> indexesAndConstraintsForNode() throws IndexNotFoundKernelException {
        try ( Statement ignore = tx.acquireStatement() ) {
            TokenRead tokenRead = tx.tokenRead();
            TokenNameLookup tokens = new SilentTokenNameLookup(tokenRead);

            SchemaRead schemaRead = tx.schemaRead();
            Iterable<IndexReference> indexesIterator = () -> schemaRead.indexesGetAll();

            Iterable<ConstraintDescriptor> constraintsIterator = () -> schemaRead.constraintsGetAll();
            Stream<IndexConstraintNodeInfo> constraintNodeInfoStream = StreamSupport.stream(constraintsIterator.spliterator(), false)
                    .filter(constraintDescriptor -> constraintDescriptor.type().equals(ConstraintDescriptor.Type.EXISTS))
                    .map(constraintDescriptor -> this.nodeInfoFromConstraintDescriptor(constraintDescriptor, tokens))
                    .sorted(Comparator.comparing(i -> i.label));

            Stream<IndexConstraintNodeInfo> indexNodeInfoStream = StreamSupport.stream(indexesIterator.spliterator(), false)
                    .map(indexReference -> this.nodeInfoFromIndexDefinition(indexReference, schemaRead, tokens))
                    .sorted(Comparator.comparing(i -> i.label));

            return Stream.of(constraintNodeInfoStream, indexNodeInfoStream).flatMap(e -> e);
        }
    }

    /**
     * Collects constraints for relationships
     *
     * @return
     */
    private Stream<ConstraintRelationshipInfo> constraintsForRelationship() {
        Schema schema = db.schema();

        return StreamSupport.stream(schema.getConstraints().spliterator(), false)
                .filter(constraintDefinition -> constraintDefinition.isConstraintType(ConstraintType.RELATIONSHIP_PROPERTY_EXISTENCE))
                .map(this::relationshipInfoFromConstraintDefinition);
    }


    /**
     * ConstraintInfo info from ConstraintDescriptor
     *
     * @param constraintDescriptor
     * @param tokens
     * @return
     */
    private IndexConstraintNodeInfo nodeInfoFromConstraintDescriptor(ConstraintDescriptor constraintDescriptor, TokenNameLookup tokens) {
        String labelName =  tokens.labelGetName(constraintDescriptor.schema().keyId());
        List<String> properties = new ArrayList<>();
        Arrays.stream(constraintDescriptor.schema().getPropertyIds()).forEach((i) -> properties.add(tokens.propertyKeyGetName(i)));
        return new IndexConstraintNodeInfo(
                // Pretty print for index name
                String.format(":%s(%s)", labelName, StringUtils.join(properties, ",")),
                labelName,
                properties,
                StringUtils.EMPTY,
                ConstraintType.NODE_PROPERTY_EXISTENCE.toString(),
                "NO FAILURE",
                0,
                0,
                0,
                constraintDescriptor.userDescription(tokens)
        );
    }

    /**
     * Index info from IndexDefinition
     *
     * @param indexReference
     * @param schemaRead
     * @param tokens
     * @return
     */
    private IndexConstraintNodeInfo nodeInfoFromIndexDefinition(IndexReference indexReference, SchemaRead schemaRead, TokenNameLookup tokens){
        String labelName =  tokens.labelGetName(indexReference.label());
        List<String> properties = new ArrayList<>();
        Arrays.stream(indexReference.properties()).forEach((i) -> properties.add(tokens.propertyKeyGetName(i)));
        try {
            return new IndexConstraintNodeInfo(
                    // Pretty print for index name
                    String.format(":%s(%s)", labelName, StringUtils.join(properties, ",")),
                    labelName,
                    properties,
                    schemaRead.indexGetState(indexReference).toString(),
                    !indexReference.isUnique() ? "INDEX" : "UNIQUENESS",
                    schemaRead.indexGetState(indexReference).equals(InternalIndexState.FAILED) ? schemaRead.indexGetFailure(indexReference) : "NO FAILURE",
                    schemaRead.indexGetPopulationProgress(indexReference).getCompleted() / schemaRead.indexGetPopulationProgress(indexReference).getTotal() * 100,
                    schemaRead.indexSize(indexReference),
                    schemaRead.indexUniqueValuesSelectivity(indexReference),
                    indexReference.userDescription(tokens)
            );
        } catch(IndexNotFoundKernelException e) {
            return new IndexConstraintNodeInfo(
                    // Pretty print for index name
                    String.format(":%s(%s)", labelName, StringUtils.join(properties, ",")),
                    labelName,
                    properties,
                    "NOT_FOUND",
                    !indexReference.isUnique() ? "INDEX" : "UNIQUENESS",
                    "NOT_FOUND",
                    0,0,0,
                    indexReference.userDescription(tokens)
            );
        }
    }

    /**
     * Constraint info from ConstraintDefinition for relationships
     *
     * @param constraintDefinition
     * @return
     */
    private ConstraintRelationshipInfo relationshipInfoFromConstraintDefinition(ConstraintDefinition constraintDefinition) {
        return new ConstraintRelationshipInfo(
                String.format("CONSTRAINT %s", constraintDefinition.toString()),
                constraintDefinition.getConstraintType().name(),
                Iterables.asList(constraintDefinition.getPropertyKeys()),
                ""
        );
    }
}
