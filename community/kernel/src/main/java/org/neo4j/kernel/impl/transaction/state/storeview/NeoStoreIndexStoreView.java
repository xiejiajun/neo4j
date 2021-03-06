/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.transaction.state.storeview;

import org.eclipse.collections.api.set.primitive.MutableIntSet;

import java.util.function.IntPredicate;

import org.neo4j.helpers.collection.Visitor;
import org.neo4j.internal.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.api.labelscan.NodeLabelUpdate;
import org.neo4j.kernel.impl.api.CountsAccessor;
import org.neo4j.kernel.impl.api.index.EntityUpdates;
import org.neo4j.kernel.impl.api.index.IndexStoreView;
import org.neo4j.kernel.impl.api.index.StoreScan;
import org.neo4j.kernel.impl.locking.LockService;
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.RecordStorageReader;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.NodeStore;
import org.neo4j.kernel.impl.store.PropertyStore;
import org.neo4j.kernel.impl.store.RelationshipStore;
import org.neo4j.kernel.impl.store.counts.CountsTracker;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.PrimitiveRecord;
import org.neo4j.kernel.impl.store.record.PropertyBlock;
import org.neo4j.kernel.impl.store.record.PropertyRecord;
import org.neo4j.kernel.impl.store.record.Record;
import org.neo4j.register.Register.DoubleLongRegister;
import org.neo4j.storageengine.api.EntityType;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.Values;

import static org.neo4j.kernel.impl.store.NodeLabelsField.parseLabelsField;
import static org.neo4j.kernel.impl.store.record.RecordLoad.FORCE;

/**
 * Node store view that will always visit all nodes during store scan.
 */
public class NeoStoreIndexStoreView implements IndexStoreView
{
    protected final PropertyStore propertyStore;
    protected final NodeStore nodeStore;
    protected final RelationshipStore relationshipStore;
    protected final LockService locks;
    private final CountsTracker counts;
    private final NeoStores neoStores;

    public NeoStoreIndexStoreView( LockService locks, NeoStores neoStores )
    {
        this.locks = locks;
        this.neoStores = neoStores;
        this.propertyStore = neoStores.getPropertyStore();
        this.nodeStore = neoStores.getNodeStore();
        this.relationshipStore = neoStores.getRelationshipStore();
        this.counts = neoStores.getCounts();
    }

    @Override
    public DoubleLongRegister indexUpdatesAndSize( long indexId, DoubleLongRegister output )
    {
        return counts.indexUpdatesAndSize( indexId, output );
    }

    @Override
    public void replaceIndexCounts( long indexId, long uniqueElements, long maxUniqueElements, long indexSize )
    {
        try ( CountsAccessor.IndexStatsUpdater updater = counts.updateIndexCounts() )
        {
            updater.replaceIndexSample( indexId, uniqueElements, maxUniqueElements );
            updater.replaceIndexUpdateAndSize( indexId, 0L, indexSize );
        }
    }

    @Override
    public void incrementIndexUpdates( long indexId, long updatesDelta )
    {
        try ( CountsAccessor.IndexStatsUpdater updater = counts.updateIndexCounts() )
        {
            updater.incrementIndexUpdates( indexId, updatesDelta );
        }
    }

    @Override
    public DoubleLongRegister indexSample( long indexId, DoubleLongRegister output )
    {
        return counts.indexSample( indexId, output );
    }

    @Override
    public <FAILURE extends Exception> StoreScan<FAILURE> visitNodes(
            final int[] labelIds, IntPredicate propertyKeyIdFilter,
            final Visitor<EntityUpdates, FAILURE> propertyUpdatesVisitor,
            final Visitor<NodeLabelUpdate, FAILURE> labelUpdateVisitor,
            boolean forceStoreScan )
    {
        return new StoreViewNodeStoreScan<>( new RecordStorageReader( neoStores ), locks, labelUpdateVisitor,
                propertyUpdatesVisitor, labelIds, propertyKeyIdFilter );
    }

    @Override
    public <FAILURE extends Exception> StoreScan<FAILURE> visitRelationships( final int[] relationshipTypeIds, IntPredicate propertyKeyIdFilter,
            final Visitor<EntityUpdates,FAILURE> propertyUpdatesVisitor )
    {
        return new RelationshipStoreScan<>( new RecordStorageReader( neoStores ), locks, propertyUpdatesVisitor, relationshipTypeIds, propertyKeyIdFilter );
    }

    @Override
    public EntityUpdates nodeAsUpdates( long nodeId )
    {
        NodeRecord node = nodeStore.getRecord( nodeId, nodeStore.newRecord(), FORCE );
        if ( !node.inUse() )
        {
            return null;
        }
        long firstPropertyId = node.getNextProp();
        if ( firstPropertyId == Record.NO_NEXT_PROPERTY.intValue() )
        {
            return null; // no properties => no updates (it's not going to be in any index)
        }
        long[] labels = parseLabelsField( node ).get( nodeStore );
        if ( labels.length == 0 )
        {
            return null; // no labels => no updates (it's not going to be in any index)
        }
        EntityUpdates.Builder update = EntityUpdates.forEntity( nodeId ).withTokens( labels );
        for ( PropertyRecord propertyRecord : propertyStore.getPropertyRecordChain( firstPropertyId ) )
        {
            for ( PropertyBlock property : propertyRecord )
            {
                Value value = property.getType().value( property, propertyStore );
                update.added( property.getKeyIndexId(), value );
            }
        }
        return update.build();
    }

    @Override
    public Value getNodePropertyValue( long nodeId, int propertyKeyId ) throws EntityNotFoundException
    {
        NodeRecord node = nodeStore.getRecord( nodeId, nodeStore.newRecord(), FORCE );
        if ( !node.inUse() )
        {
            throw new EntityNotFoundException( EntityType.NODE, nodeId );
        }
        long firstPropertyId = node.getNextProp();
        if ( firstPropertyId == Record.NO_NEXT_PROPERTY.intValue() )
        {
            return Values.NO_VALUE;
        }
        for ( PropertyRecord propertyRecord : propertyStore.getPropertyRecordChain( firstPropertyId ) )
        {
            PropertyBlock propertyBlock = propertyRecord.getPropertyBlock( propertyKeyId );
            if ( propertyBlock != null )
            {
                return propertyBlock.newPropertyValue( propertyStore );
            }
        }
        return Values.NO_VALUE;
    }

    @Override
    public void loadProperties( long entityId, EntityType type, MutableIntSet propertyIds, PropertyLoadSink sink )
    {
        PrimitiveRecord entity;
        if ( type == EntityType.NODE )
        {
            entity = nodeStore.getRecord( entityId, nodeStore.newRecord(), FORCE );
        }
        else
        {
            entity = relationshipStore.getRecord( entityId, relationshipStore.newRecord(), FORCE );
        }
        if ( !entity.inUse() )
        {
            return;
        }
        long firstPropertyId = entity.getNextProp();
        if ( firstPropertyId == Record.NO_NEXT_PROPERTY.intValue() )
        {
            return;
        }
        for ( PropertyRecord propertyRecord : propertyStore.getPropertyRecordChain( firstPropertyId ) )
        {
            for ( PropertyBlock block : propertyRecord )
            {
                int currentPropertyId = block.getKeyIndexId();
                if ( propertyIds.remove( currentPropertyId ) )
                {
                    Value currentValue = block.getType().value( block, propertyStore );
                    sink.onProperty( currentPropertyId, currentValue );
                }
            }
        }
    }
}
