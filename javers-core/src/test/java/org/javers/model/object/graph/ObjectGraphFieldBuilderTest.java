package org.javers.model.object.graph;

import org.javers.core.model.DummyAddress;
import org.javers.core.model.DummyUser;
import org.javers.core.model.DummyUserDetails;
import org.javers.model.mapping.EntityFactory;
import org.javers.model.mapping.EntityManager;
import org.javers.model.mapping.FieldBasedPropertyScanner;
import org.javers.model.mapping.type.TypeMapper;
import org.junit.Before;

import static org.javers.test.builder.TypeMapperTestBuilder.typeMapper;

/**
 * @author Pawel Cierpiatka <pawel.cierpiatka@gmail.com>
 */
public class ObjectGraphFieldBuilderTest extends ObjectGraphBuilderTest {

    @Before
    public void setUp() {
        TypeMapper mapper = new TypeMapper();
        FieldBasedPropertyScanner scanner = new FieldBasedPropertyScanner(mapper);
        EntityFactory ef = new EntityFactory(scanner);
        entityManager = buildEntityManager(ef,mapper);
    }

}
