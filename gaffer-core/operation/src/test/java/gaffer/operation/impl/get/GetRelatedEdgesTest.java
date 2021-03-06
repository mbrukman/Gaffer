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

package gaffer.operation.impl.get;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import gaffer.data.elementdefinition.view.View;
import gaffer.exception.SerialisationException;
import gaffer.jsonserialisation.JSONSerialiser;
import gaffer.operation.GetOperation;
import gaffer.operation.OperationTest;
import gaffer.operation.data.EntitySeed;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;


public class GetRelatedEdgesTest implements OperationTest {
    private static final JSONSerialiser serialiser = new JSONSerialiser();

    @Test
    public void shouldSetSeedMatchingTypeToRelated() {
        // Given
        final EntitySeed seed1 = new EntitySeed("identifier1");

        // When
        final GetRelatedEdges<EntitySeed> op = new GetRelatedEdges<>(Collections.singletonList(seed1));

        // Then
        assertEquals(GetOperation.SeedMatchingType.RELATED, op.getSeedMatching());
    }

    @Test
    @Override
    public void shouldSerialiseAndDeserialiseOperation() throws SerialisationException {
        // Given
        final EntitySeed seed1 = new EntitySeed("identifier1");
        final EntitySeed seed2 = new EntitySeed("identifier2");
        final GetRelatedEdges<EntitySeed> op = new GetRelatedEdges<>(Arrays.asList(seed1, seed2));

        // When
        byte[] json = serialiser.serialise(op, true);
        final GetRelatedEdges deserialisedOp = serialiser.deserialise(json, GetRelatedEdges.class);

        // Then
        final Iterator itr = deserialisedOp.getSeeds().iterator();
        assertEquals(seed1, itr.next());
        assertEquals(seed2, itr.next());
        assertFalse(itr.hasNext());
    }

    @Test
    @Override
    public void builderShouldCreatePopulatedOperation() {
        GetRelatedEdges<EntitySeed> getRelatedEdges = new GetRelatedEdges.Builder<EntitySeed>()
                .addSeed(new EntitySeed("A"))
                .inOutType(GetOperation.IncludeIncomingOutgoingType.OUTGOING)
                .option("testOption", "true")
                .populateProperties(true)
                .view(new View.Builder()
                        .edge("testEdgeGroup")
                        .build())
                .build();
        assertTrue(getRelatedEdges.isPopulateProperties());
        assertEquals(GetOperation.IncludeIncomingOutgoingType.OUTGOING, getRelatedEdges.getIncludeIncomingOutGoing());
        assertEquals("true", getRelatedEdges.getOption("testOption"));
        assertNotNull(getRelatedEdges.getView());
    }
}
