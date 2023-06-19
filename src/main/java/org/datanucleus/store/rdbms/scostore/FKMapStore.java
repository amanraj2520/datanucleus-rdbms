/**********************************************************************
Copyright (c) 2007 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.scostore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.FetchPlan;
import org.datanucleus.PersistableObjectType;
import org.datanucleus.api.ApiAdapter;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.DiscriminatorStrategy;
import org.datanucleus.metadata.MapMetaData;
import org.datanucleus.metadata.MapMetaData.MapType;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.FieldValues;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.query.expression.Expression;
import org.datanucleus.store.rdbms.exceptions.ClassDefinitionException;
import org.datanucleus.store.rdbms.mapping.MappingHelper;
import org.datanucleus.store.rdbms.mapping.MappingType;
import org.datanucleus.store.rdbms.mapping.java.EmbeddedKeyPCMapping;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.mapping.java.ReferenceMapping;
import org.datanucleus.store.rdbms.mapping.java.SerialisedMapping;
import org.datanucleus.store.rdbms.mapping.java.SerialisedPCMapping;
import org.datanucleus.store.rdbms.mapping.java.SerialisedReferenceMapping;
import org.datanucleus.store.rdbms.JDBCUtils;
import org.datanucleus.store.rdbms.RDBMSStoreManager;
import org.datanucleus.store.rdbms.SQLController;
import org.datanucleus.store.rdbms.query.PersistentClassROF;
import org.datanucleus.store.rdbms.query.StatementClassMapping;
import org.datanucleus.store.rdbms.query.StatementMappingIndex;
import org.datanucleus.store.rdbms.query.StatementParameterMapping;
import org.datanucleus.store.rdbms.sql.DiscriminatorStatementGenerator;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.SQLStatementHelper;
import org.datanucleus.store.rdbms.sql.SQLTable;
import org.datanucleus.store.rdbms.sql.SelectStatement;
import org.datanucleus.store.rdbms.sql.SelectStatementGenerator;
import org.datanucleus.store.rdbms.sql.UnionStatementGenerator;
import org.datanucleus.store.rdbms.sql.SQLJoin.JoinType;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.table.DatastoreClass;
import org.datanucleus.store.types.scostore.CollectionStore;
import org.datanucleus.store.types.scostore.MapStore;
import org.datanucleus.store.types.scostore.SetStore;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

/**
 * Implementation of an {@link MapStore} where either the value has a FK to the owner (and the key stored in the value), 
 * or whether the key has a FK to the owner (and the value stored in the key).
 */
public class FKMapStore<K, V> extends AbstractMapStore<K, V>
{
    /** Table storing the values (either key table, or value table). */
    protected DatastoreClass mapTable;

    /** Statement for updating a foreign key for the map. */
    private String updateFkStmt;

    /** JDBC statement to use for retrieving the value of the map for a key (locking). */
    private String getStmtLocked = null;

    /** JDBC statement to use for retrieving the value of the map for a key (not locking). */
    private String getStmtUnlocked = null;

    private StatementClassMapping getMappingDef = null;
    private StatementParameterMapping getMappingParams = null;

    /** Field number of owner link in key/value class. */
    private final int ownerFieldNumber;

    /** Field number of key in value class (when key stored in value). */
    protected int keyFieldNumber = -1;

    /** Field number of value in key class (when value stored in key). */
    private int valueFieldNumber = -1;

    /**
     * Constructor for the backing store for an FK Map for RDBMS.
     * @param mmd Field Meta-Data for the Map field.
     * @param storeMgr The Store Manager we are using.
     * @param clr The ClassLoaderResolver
     */
    public FKMapStore(AbstractMemberMetaData mmd, RDBMSStoreManager storeMgr, ClassLoaderResolver clr)
    {
        super(storeMgr, clr);
        setOwner(mmd);

        MapMetaData mapmd = (MapMetaData)mmd.getContainer();
        if (mapmd == null)
        {
            // No <map> specified for this field!
            throw new NucleusUserException(Localiser.msg("056002", mmd.getFullFieldName()));
        }

        // Check whether we store the key in the value, or the value in the key
        boolean keyStoredInValue = false;
        if (mmd.getKeyMetaData() != null && mmd.getKeyMetaData().getMappedBy() != null)
        {
            keyStoredInValue = true;
        }
        else if (mmd.getValueMetaData() != null && mmd.getValueMetaData().getMappedBy() == null)
        {
            // No mapped-by specified on either key or value so we dont know what to do with this relation!
            throw new NucleusUserException(Localiser.msg("056071", mmd.getFullFieldName()));
        }
        else
        {
            // Should throw an exception since must store key in value or value in key
        }

        // Load the key and value classes
        this.keyType = mapmd.getKeyType();
        this.valueType = mapmd.getValueType();
        Class keyClass = clr.classForName(keyType);
        Class valueClass = clr.classForName(valueType);

        ApiAdapter api = getStoreManager().getApiAdapter();
        if (keyStoredInValue && !api.isPersistable(valueClass))
        {
            // key stored in value but value is not PC!
            throw new NucleusUserException(Localiser.msg("056072", mmd.getFullFieldName(), valueType));
        }
        if (!keyStoredInValue && !api.isPersistable(keyClass))
        {
            // value stored in key but key is not PC!
            throw new NucleusUserException(Localiser.msg("056073", mmd.getFullFieldName(), keyType));
        }

        String ownerFieldName = mmd.getMappedBy();
        if (keyStoredInValue)
        {
            // Key = field in value, Value = PC
            valueCmd = storeMgr.getNucleusContext().getMetaDataManager().getMetaDataForClass(valueClass, clr);
            if (valueCmd == null)
            {
                // Value has no MetaData!
                throw new NucleusUserException(Localiser.msg("056070", valueType, mmd.getFullFieldName()));
            }

            DatastoreClass joiningTable = storeMgr.getDatastoreClass(valueType, clr);
            valueMapping  = storeMgr.getDatastoreClass(valueType, clr).getIdMapping();
            valuesAreEmbedded = false;
            valuesAreSerialised = false;

            if (mmd.getMappedBy() != null)
            {
                // 1-N bidirectional : The value class has a field for the owner.
                AbstractMemberMetaData vofmd = valueCmd.getMetaDataForMember(ownerFieldName);
                if (vofmd == null)
                {
                    throw new NucleusUserException(Localiser.msg("056067", mmd.getFullFieldName(), ownerFieldName, valueClass.getName()));
                }

                // Check that the type of the value "mapped-by" field is consistent with the owner type
                if (!clr.isAssignableFrom(vofmd.getType(), mmd.getAbstractClassMetaData().getFullClassName()))
                {
                    throw new NucleusUserException(Localiser.msg("056068", mmd.getFullFieldName(),
                        vofmd.getFullFieldName(), vofmd.getTypeName(), mmd.getAbstractClassMetaData().getFullClassName()));
                }

                ownerFieldNumber = valueCmd.getAbsolutePositionOfMember(ownerFieldName);
                ownerMapping = joiningTable.getMemberMapping(vofmd);
                if (ownerMapping == null)
                {
                    throw new NucleusUserException(Localiser.msg("RDBMS.SCO.Map.InverseOwnerMappedByFieldNotPresent", 
                        mmd.getAbstractClassMetaData().getFullClassName(), mmd.getName(), valueType, ownerFieldName));
                }
                if (isEmbeddedMapping(ownerMapping))
                {
                    throw new NucleusUserException(Localiser.msg("056055", ownerFieldName, valueType, vofmd.getTypeName(), mmd.getClassName()));
                }
            }
            else
            {
                // 1-N Unidirectional : The value class knows nothing about the owner
                ownerFieldNumber = -1;
                ownerMapping = joiningTable.getExternalMapping(mmd, MappingType.EXTERNAL_FK);
                if (ownerMapping == null)
                {
                    throw new NucleusUserException(Localiser.msg("056056", mmd.getAbstractClassMetaData().getFullClassName(), mmd.getName(), valueType));
                }
            }

            if (mmd.getKeyMetaData() == null || mmd.getKeyMetaData().getMappedBy() == null)
            {
                throw new NucleusUserException(Localiser.msg("056050", valueClass.getName()));
            }

            AbstractMemberMetaData vkfmd = null;
            String key_field_name = mmd.getKeyMetaData().getMappedBy();
            if (key_field_name != null)
            {
                // check if key field exists in the ClassMetaData for the element-value type
                AbstractClassMetaData vkCmd = storeMgr.getMetaDataManager().getMetaDataForClass(valueClass, clr);
                vkfmd = (vkCmd != null ? vkCmd.getMetaDataForMember(key_field_name) : null);
                if (vkfmd == null)
                {
                    throw new NucleusUserException(Localiser.msg("056052", valueClass.getName(), key_field_name));
                }
            }
            if (vkfmd == null)
            {
                throw new ClassDefinitionException(Localiser.msg("056050", mmd.getFullFieldName()));
            }

            // Check that the key type is correct for the declared type
            if (!ClassUtils.typesAreCompatible(vkfmd.getType(), keyType, clr))
            {
                throw new NucleusUserException(Localiser.msg("056051", mmd.getFullFieldName(), keyType, vkfmd.getType().getName()));
            }

            // Set up key field
            String keyFieldName = vkfmd.getName();
            keyFieldNumber = valueCmd.getAbsolutePositionOfMember(keyFieldName);
            keyMapping = joiningTable.getMemberMapping(valueCmd.getMetaDataForManagedMemberAtAbsolutePosition(keyFieldNumber));
            if (keyMapping == null)
            {
                throw new NucleusUserException(Localiser.msg("056053", mmd.getAbstractClassMetaData().getFullClassName(), mmd.getName(), valueType, keyFieldName));
            }

            if (!keyMapping.hasSimpleDatastoreRepresentation())
            {
                // Check the type of the mapping
                throw new NucleusUserException("Invalid field type for map key field: " + mmd.getFullFieldName());
            }
            keysAreEmbedded = isEmbeddedMapping(keyMapping);
            keysAreSerialised = isEmbeddedMapping(keyMapping);

            mapTable = joiningTable;
            if (mmd.getMappedBy() != null && ownerMapping.getTable() != mapTable)
            {
                // Value and owner don't have consistent tables so use the one with the mapping
                // e.g map value is subclass, yet superclass has the link back to the owner
                mapTable = (DatastoreClass) ownerMapping.getTable();
            }
        }
        else
        {
            // Key = PC, Value = field in key
            keyCmd = storeMgr.getNucleusContext().getMetaDataManager().getMetaDataForClass(keyClass, clr);
            if (keyCmd == null)
            {
                // Key has no MetaData!
                throw new NucleusUserException(Localiser.msg("056069", keyType, mmd.getFullFieldName()));
            }

            DatastoreClass joiningTable = storeMgr.getDatastoreClass(keyType, clr);
            keyMapping  = storeMgr.getDatastoreClass(keyType, clr).getIdMapping();
            keysAreEmbedded = false;
            keysAreSerialised = false;

            if (mmd.getMappedBy() != null)
            {
                // 1-N bidirectional : The key class has a field for the owner.
                AbstractMemberMetaData kofmd = keyCmd.getMetaDataForMember(ownerFieldName);
                if (kofmd == null)
                {
                    throw new NucleusUserException(Localiser.msg("056067", mmd.getFullFieldName(), ownerFieldName, keyClass.getName()));
                }

                // Check that the type of the key "mapped-by" field is consistent with the owner type
                if (!ClassUtils.typesAreCompatible(kofmd.getType(), mmd.getAbstractClassMetaData().getFullClassName(), clr))
                {
                    throw new NucleusUserException(Localiser.msg("056068", mmd.getFullFieldName(),
                        kofmd.getFullFieldName(), kofmd.getTypeName(), mmd.getAbstractClassMetaData().getFullClassName()));
                }

                ownerFieldNumber = keyCmd.getAbsolutePositionOfMember(ownerFieldName);
                ownerMapping = joiningTable.getMemberMapping(kofmd);
                if (ownerMapping == null)
                {
                    throw new NucleusUserException(Localiser.msg("RDBMS.SCO.Map.InverseOwnerMappedByFieldNotPresent", 
                        mmd.getAbstractClassMetaData().getFullClassName(), mmd.getName(), keyType, ownerFieldName));
                }
                if (isEmbeddedMapping(ownerMapping))
                {
                    throw new NucleusUserException(Localiser.msg("056055", ownerFieldName, keyType, kofmd.getTypeName(), mmd.getClassName()));
                }
            }
            else
            {
                // 1-N Unidirectional : The key class knows nothing about the owner
                ownerFieldNumber = -1;
                ownerMapping = joiningTable.getExternalMapping(mmd, MappingType.EXTERNAL_FK);
                if (ownerMapping == null)
                {
                    throw new NucleusUserException(Localiser.msg("056056", mmd.getAbstractClassMetaData().getFullClassName(), mmd.getName(), keyType));
                }
            }

            if (mmd.getValueMetaData() == null || mmd.getValueMetaData().getMappedBy() == null)
            {
                throw new NucleusUserException(Localiser.msg("056057", keyClass.getName()));
            }

            AbstractMemberMetaData vkfmd = null;
            String value_field_name = mmd.getValueMetaData().getMappedBy();
            if (value_field_name != null)
            {
                // check if value field exists in the ClassMetaData for the element-value type
                AbstractClassMetaData vkCmd = storeMgr.getMetaDataManager().getMetaDataForClass(keyClass, clr);
                vkfmd = (vkCmd != null ? vkCmd.getMetaDataForMember(value_field_name) : null);
                if (vkfmd == null)
                {
                    throw new NucleusUserException(Localiser.msg("056059", keyClass.getName(), value_field_name));
                }
            }
            if (vkfmd == null)
            {
                throw new ClassDefinitionException(Localiser.msg("056057", mmd.getFullFieldName()));
            }

            // Check that the value type is consistent with the declared type
            if (!ClassUtils.typesAreCompatible(vkfmd.getType(), valueType, clr))
            {
                throw new NucleusUserException(Localiser.msg("056058", mmd.getFullFieldName(), valueType, vkfmd.getType().getName()));
            }

            // Set up value field
            String valueFieldName = vkfmd.getName();
            valueFieldNumber = keyCmd.getAbsolutePositionOfMember(valueFieldName);
            valueMapping = joiningTable.getMemberMapping(keyCmd.getMetaDataForManagedMemberAtAbsolutePosition(valueFieldNumber));
            if (valueMapping == null)
            {
                throw new NucleusUserException(Localiser.msg("056054", mmd.getAbstractClassMetaData().getFullClassName(), mmd.getName(), keyType, valueFieldName));
            }

            if (!valueMapping.hasSimpleDatastoreRepresentation())
            {
                // Check the type of the mapping
                throw new NucleusUserException("Invalid field type for map value field: " + mmd.getFullFieldName());
            }
            valuesAreEmbedded = isEmbeddedMapping(valueMapping);
            valuesAreSerialised = isEmbeddedMapping(valueMapping);

            mapTable = joiningTable;
            if (mmd.getMappedBy() != null && ownerMapping.getTable() != mapTable)
            {
                // Key and owner don't have consistent tables so use the one with the mapping
                // e.g map key is subclass, yet superclass has the link back to the owner
                mapTable = (DatastoreClass) ownerMapping.getTable();
            }
        }

        // Create any statements that can be cached
        initialise();
    }

    protected void initialise()
    {
        containsValueStmt = getContainsValueStmt(getOwnerMapping(), getValueMapping(), mapTable);
        updateFkStmt = getUpdateFkStmt();
    }

    /**
     * Utility to update a foreign-key in the value in the case of a unidirectional 1-N relationship.
     * @param sm StateManager for the owner
     * @param value The value to update
     * @param owner The owner object to set in the FK
     * @return Whether it was performed successfully
     */
    private boolean updateValueFk(DNStateManager sm, Object value, Object owner)
    {
        if (value == null)
        {
            return false;
        }
        validateValueForWriting(sm, value);
        return updateValueFkInternal(sm, value, owner);
    }

    /**
     * Utility to update a foreign-key in the key in the case of
     * a unidirectional 1-N relationship.
     * @param sm StateManager for the owner
     * @param key The key to update
     * @param owner The owner object to set in the FK
     * @return Whether it was performed successfully
     */
    private boolean updateKeyFk(DNStateManager sm, Object key, Object owner)
    {
        if (key == null)
        {
            return false;
        }
        validateKeyForWriting(sm, key);
        return updateKeyFkInternal(sm, key, owner);
    }

    /**
     * Utility to validate the type of a value for storing in the Map.
     * @param value The value to check.
     * @param clr The ClassLoaderResolver
     **/
    protected void validateValueType(ClassLoaderResolver clr, Object value)
    {
        if (value == null)
        {
            throw new NullPointerException(Localiser.msg("056063"));
        }

        super.validateValueType(clr, value);
    }

    @Override
    public V put(final DNStateManager sm, final K newKey, V newValue)
    {
        ExecutionContext ec = sm.getExecutionContext();
        if (keyFieldNumber >= 0)
        {
            validateKeyForWriting(sm, newKey);
            validateValueType(ec.getClassLoaderResolver(), newValue);
        }
        else
        {
            validateKeyType(ec.getClassLoaderResolver(), newKey);
            validateValueForWriting(sm, newValue);
        }

        // Check if there is an existing value for this key
        V oldValue = get(sm, newKey);
        if (oldValue != newValue)
        {
            if (valueCmd != null)
            {
                if (oldValue != null && !oldValue.equals(newValue))
                {
                    // Key is stored in the value and the value has changed so remove the old value
                    removeValue(sm, newKey, oldValue);
                }

                final Object newOwner = sm.getObject();

                if (ec.getApiAdapter().isPersistent(newValue))
                {
                    /*
                     * The new value is already persistent.
                     *
                     * "Put" the new value in the map by updating its owner and key
                     * fields to the appropriate values.  This is done with the same
                     * methods the PC itself would use if the application code
                     * modified the fields.  It should result in no actual database
                     * activity if the fields were already set to the right values.
                     */
                    if (ec != ec.getApiAdapter().getExecutionContext(newValue))
                    {
                        throw new NucleusUserException(Localiser.msg("RDBMS.SCO.Map.WriteValueInvalidWithDifferentPM"), ec.getApiAdapter().getIdForObject(newValue));
                    }

                    DNStateManager vsm = ec.findStateManager(newValue);
                    
                    // Ensure the current owner field is loaded, and replace with new value
                    if (ownerFieldNumber >= 0)
                    {
                        vsm.isLoaded(ownerFieldNumber);
                        Object oldOwner = vsm.provideField(ownerFieldNumber);
                        vsm.replaceFieldMakeDirty(ownerFieldNumber, newOwner);
                        if (ec.getManageRelations())
                        {
                            ec.getRelationshipManager(vsm).relationChange(ownerFieldNumber, oldOwner, newOwner);
                        }
                    }
                    else
                    {
                        updateValueFk(sm, newValue, newOwner);
                    }

                    // Ensure the current key field is loaded, and replace with new value
                    vsm.isLoaded(keyFieldNumber);
                    Object oldKey = vsm.provideField(keyFieldNumber);
                    vsm.replaceFieldMakeDirty(keyFieldNumber, newKey);
                    if (ec.getManageRelations())
                    {
                        ec.getRelationshipManager(vsm).relationChange(keyFieldNumber, oldKey, newKey);
                    }
                }
                else
                {                  
                    /*
                     * The new value is not yet persistent.
                     *
                     * Update its owner and key fields to the appropriate values and
                     * *then* make it persistent.  Making the changes before DB
                     * insertion avoids an unnecessary UPDATE allows the owner
                     * and/or key fields to be non-nullable.
                     */
                    ec.persistObjectInternal(newValue, new FieldValues()
                        {
                        public void fetchFields(DNStateManager vsm)
                        {
                            if (ownerFieldNumber >= 0)
                            {
                                vsm.replaceFieldMakeDirty(ownerFieldNumber, newOwner);
                            }
                            vsm.replaceFieldMakeDirty(keyFieldNumber, newKey);

                            JavaTypeMapping externalFKMapping = mapTable.getExternalMapping(ownerMemberMetaData, MappingType.EXTERNAL_FK);
                            if (externalFKMapping != null)
                            {
                                // Set the owner in the value object where appropriate
                                vsm.setAssociatedValue(externalFKMapping, sm.getObject());
                            }
                        }
                        public void fetchNonLoadedFields(DNStateManager sm)
                        {
                        }
                        public FetchPlan getFetchPlanForLoading()
                        {
                            return null;
                        }
                        }, PersistableObjectType.PC);
                }
            }
            else
            {
                // Value is stored in the key
                final Object newOwner = sm.getObject();

                if (ec.getApiAdapter().isPersistent(newKey))
                {
                    /*
                     * The new key is already persistent.
                     *
                     * "Put" the new key in the map by updating its owner and value
                     * fields to the appropriate values. This is done with the same
                     * methods the PC itself would use if the application code
                     * modified the fields. It should result in no actual database
                     * activity if the fields were already set to the right values.
                     */
                    if (ec != ec.getApiAdapter().getExecutionContext(newKey))
                    {
                        throw new NucleusUserException(Localiser.msg("056060"),
                            ec.getApiAdapter().getIdForObject(newKey));
                    }

                    DNStateManager valSM = ec.findStateManager(newKey);

                    // Ensure the current owner field is loaded, and replace with new key
                    if (ownerFieldNumber >= 0)
                    {
                        valSM.isLoaded(ownerFieldNumber);
                        Object oldOwner = valSM.provideField(ownerFieldNumber);
                        valSM.replaceFieldMakeDirty(ownerFieldNumber, newOwner);
                        if (ec.getManageRelations())
                        {
                            ec.getRelationshipManager(valSM).relationChange(ownerFieldNumber, oldOwner, newOwner);
                        }
                    }
                    else
                    {
                        updateKeyFk(sm, newKey, newOwner);
                    }

                    // Ensure the current value field is loaded, and replace with new value
                    valSM.isLoaded(valueFieldNumber);
                    oldValue = (V) valSM.provideField(valueFieldNumber); // TODO Should we update the local variable ?
                    valSM.replaceFieldMakeDirty(valueFieldNumber, newValue);
                    if (ec.getManageRelations())
                    {
                        ec.getRelationshipManager(valSM).relationChange(valueFieldNumber, oldValue, newValue);
                    }
                }
                else
                {
                    /*
                     * The new key is not yet persistent.
                     *
                     * Update its owner and key fields to the appropriate values and
                     * *then* make it persistent.  Making the changes before DB
                     * insertion avoids an unnecessary UPDATE allows the owner
                     * and/or key fields to be non-nullable.
                     */
                    final Object newValueObj = newValue;
                    ec.persistObjectInternal(newKey, new FieldValues()
                        {
                        public void fetchFields(DNStateManager vsm)
                        {
                            if (ownerFieldNumber >= 0)
                            {
                                vsm.replaceFieldMakeDirty(ownerFieldNumber, newOwner);
                            }
                            vsm.replaceFieldMakeDirty(valueFieldNumber, newValueObj);

                            JavaTypeMapping externalFKMapping = mapTable.getExternalMapping(ownerMemberMetaData, MappingType.EXTERNAL_FK);
                            if (externalFKMapping != null)
                            {
                                // Set the owner in the value object where appropriate
                                vsm.setAssociatedValue(externalFKMapping, sm.getObject());
                            }
                        }
                        public void fetchNonLoadedFields(DNStateManager sm)
                        {
                        }
                        public FetchPlan getFetchPlanForLoading()
                        {
                            return null;
                        }
                        }, PersistableObjectType.PC
                    );

                    /*if (ownerFieldNumber < 0)
                    {
                        // TODO Think about removing this since we set the associated owner here
                        updateKeyFk(sm, newKey, newOwner);
                    }*/
                }
            }
        }

        // TODO Cater for key being PC and having delete-dependent
        if (ownerMemberMetaData.getMap().isDependentValue() && oldValue != null)
        {
            // Delete the old value if it is no longer contained and is dependent
            if (!containsValue(sm, oldValue))
            {
                ec.deleteObjectInternal(oldValue);
            }
        }

        return oldValue;
    }

    @Override
    public V remove(DNStateManager sm, Object key)
    {
        if (!allowNulls && key == null)
        {
            // Just return
            return null;
        }

        V oldValue = get(sm, key);
        remove(sm, key, oldValue);
        return oldValue;
    }

    @Override
    public void remove(DNStateManager sm, Object key, Object oldValue)
    {
        ExecutionContext ec = sm.getExecutionContext();
        if (keyFieldNumber >= 0)
        {
            // Key stored in value
            if (oldValue != null)
            {
                boolean deletingValue = false;
                DNStateManager valueSM = ec.findStateManager(oldValue);
                if (ownerMemberMetaData.getMap().isDependentValue())
                {
                    // Delete the value if it is dependent
                    deletingValue = true;
                    ec.deleteObjectInternal(oldValue);
                    valueSM.flush();
                }
                else if (ownerMapping.isNullable())
                {
                    // Null the owner FK
                    if (ownerFieldNumber >= 0)
                    {
                        // Update the field in the value
                        Object oldOwner = valueSM.provideField(ownerFieldNumber);
                        valueSM.replaceFieldMakeDirty(ownerFieldNumber, null);
                        valueSM.flush();
                        if (ec.getManageRelations())
                        {
                            ec.getRelationshipManager(valueSM).relationChange(ownerFieldNumber, oldOwner, null);
                        }
                    }
                    else
                    {
                        // Update the external FK in the value in the datastore
                        updateValueFkInternal(sm, oldValue, null);
                    }
                }
                else
                {
                    // Not nullable, so must delete since no other way of removing from map
                    deletingValue = true;
                    ec.deleteObjectInternal(oldValue);
                    valueSM.flush();
                }

                if (ownerMemberMetaData.getMap().isDependentKey())
                {
                    // Delete the key since it is dependent
                    if (!deletingValue)
                    {
                        // Null FK in value to key
                        if (keyMapping.isNullable())
                        {
                            valueSM.replaceFieldMakeDirty(keyFieldNumber, null);
                            valueSM.flush();
                            if (ec.getManageRelations())
                            {
                                ec.getRelationshipManager(valueSM).relationChange(keyFieldNumber, key, null);
                            }
                        }
                    }
                    ec.deleteObjectInternal(key);
                    DNStateManager keySM = ec.findStateManager(key);
                    keySM.flush();
                }
            }
        }
        else
        {
            // Value stored in key
            if (key != null)
            {
                boolean deletingKey = false;
                DNStateManager keySM = ec.findStateManager(key);
                if (ownerMemberMetaData.getMap().isDependentKey())
                {
                    // Delete the key if it is dependent
                    deletingKey = true;
                    ec.deleteObjectInternal(key);
                    keySM.flush();
                }
                else if (ownerMapping.isNullable())
                {
                    // Null the owner FK
                    if (ownerFieldNumber >= 0)
                    {
                        // Update the field in the key
                        Object oldOwner = keySM.provideField(ownerFieldNumber);
                        keySM.replaceFieldMakeDirty(ownerFieldNumber, null);
                        keySM.flush();
                        if (ec.getManageRelations())
                        {
                            ec.getRelationshipManager(keySM).relationChange(ownerFieldNumber, oldOwner, null);
                        }
                    }
                    else
                    {
                        // Update the external FK in the key in the datastore
                        updateKeyFkInternal(sm, key, null);
                    }
                }
                else
                {
                    // Not nullable, so must delete since no other way of removing from map
                    deletingKey = true;
                    ec.deleteObjectInternal(key);
                    keySM.flush();
                }

                if (ownerMemberMetaData.getMap().isDependentValue())
                {
                    // Delete the value since it is dependent
                    if (!deletingKey)
                    {
                        // Null FK in key to value
                        if (valueMapping.isNullable())
                        {
                            keySM.replaceFieldMakeDirty(valueFieldNumber, null);
                            keySM.flush();
                            if (ec.getManageRelations())
                            {
                                ec.getRelationshipManager(keySM).relationChange(valueFieldNumber, oldValue, null);
                            }
                        }
                    }
                    ec.deleteObjectInternal(oldValue);
                    DNStateManager valSM = ec.findStateManager(oldValue);
                    valSM.flush();
                }
            }
        }
    }

    /**
     * Utility to remove a value from the Map.
     * @param sm StateManager for the map.
     * @param key Key of the object
     * @param oldValue Value to remove
     */
    private void removeValue(DNStateManager sm, Object key, Object oldValue)
    {
        ExecutionContext ec = sm.getExecutionContext();
        
        // Null out the key and owner fields if they are nullable
        if (keyMapping.isNullable())
        {
            DNStateManager vsm = ec.findStateManager(oldValue);
            
            // Null the key field
            vsm.replaceFieldMakeDirty(keyFieldNumber, null);
            if (ec.getManageRelations())
            {
                ec.getRelationshipManager(vsm).relationChange(keyFieldNumber, key, null);
            }
            
            // Null the owner field
            if (ownerFieldNumber >= 0)
            {
                Object oldOwner = vsm.provideField(ownerFieldNumber);
                vsm.replaceFieldMakeDirty(ownerFieldNumber, null);
                if (ec.getManageRelations())
                {
                    ec.getRelationshipManager(vsm).relationChange(ownerFieldNumber, oldOwner, null);
                }
            }
            else
            {
                updateValueFk(sm, oldValue, null);
            }
        }
        // otherwise just delete the item
        else
        {
            ec.deleteObjectInternal(oldValue);
        }
    }

    @Override
    public void clear(DNStateManager sm)
    {
        // TODO Fix this. Should not be retrieving objects only to remove them since they
        // may be cached in the SCO object. But we need to utilise delete-dependent correctly too
        Iterator iter = keySetStore().iterator(sm);
        while (iter.hasNext())
        {
            Object key = iter.next();
            if (key == null && !allowNulls)
            {
                // Do nothing
            }
            else
            {
                remove(sm, key);
            }
        }
    }

    /**
     * Utility to clear the key of a value from the Map.
     * If the key is non nullable, delete the value.
     * @param sm StateManager for the map.
     * @param key Key of the object
     * @param oldValue Value to remove
     */
    public void clearKeyOfValue(DNStateManager sm, Object key, Object oldValue)
    {
        ExecutionContext ec = sm.getExecutionContext();

        if (keyMapping.isNullable())
        {
            // Null out the key and owner fields if they are nullable
            DNStateManager vsm = ec.findStateManager(oldValue);

            // Check that the value hasn't already been deleted due to being removed from the map
            if (!ec.getApiAdapter().isDeleted(oldValue))
            {
                // Null the key field
                vsm.replaceFieldMakeDirty(keyFieldNumber, null);
                if (ec.getManageRelations())
                {
                    ec.getRelationshipManager(vsm).relationChange(keyFieldNumber, key, null);
                }
            }
        }
        else
        {
            // otherwise just delete the value
            ec.deleteObjectInternal(oldValue);
        }
    }

    @Override
    public synchronized SetStore<K> keySetStore()
    {
        return new MapKeySetStore<>(mapTable, this, clr);
    }

    @Override
    public synchronized CollectionStore<V> valueCollectionStore()
    {
        return new MapValueCollectionStore<>(mapTable, this, clr);
    }

    @Override
    public synchronized SetStore<Map.Entry<K, V>> entrySetStore()
    {
        return new MapEntrySetStore<>(mapTable, this, clr);
    }

    /**
     * Generate statement for updating a Foreign Key from key/value to owner in an inverse 1-N.
     * <PRE>
     * UPDATE MAPTABLE SET FK_COL_1 = ?, FK_COL_2 = ?
     * WHERE ELEMENT_ID = ?
     * </PRE>
     * @return Statement for updating the FK in an inverse 1-N
     */
    private String getUpdateFkStmt()
    {
        StringBuilder stmt = new StringBuilder("UPDATE ");
        stmt.append(mapTable.toString());
        stmt.append(" SET ");
        for (int i=0; i<ownerMapping.getNumberOfColumnMappings(); i++)
        {
            if (i > 0)
            {
                stmt.append(",");
            }
            stmt.append(ownerMapping.getColumnMapping(i).getColumn().getIdentifier().toString());
            stmt.append(" = ");
            stmt.append(ownerMapping.getColumnMapping(i).getUpdateInputParameter());
        }
        stmt.append(" WHERE ");
        if (keyFieldNumber >= 0)
        {
            BackingStoreHelper.appendWhereClauseForMapping(stmt, valueMapping, null, true);
        }
        else
        {
            BackingStoreHelper.appendWhereClauseForMapping(stmt, keyMapping, null, true);
        }

        return stmt.toString();
    }

    protected boolean updateValueFkInternal(DNStateManager sm, Object value, Object owner)
    {
        boolean retval;
        ExecutionContext ec = sm.getExecutionContext();
        try
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, updateFkStmt, false);
                try
                {
                    int jdbcPosition = 1;
                    if (owner == null)
                    {
                        if (ownerMemberMetaData != null)
                        {
                            ownerMapping.setObject(ec, ps, MappingHelper.getMappingIndices(1,ownerMapping), null, 
                                sm, ownerMemberMetaData.getAbsoluteFieldNumber());
                        }
                        else
                        {
                            ownerMapping.setObject(ec, ps, MappingHelper.getMappingIndices(1,ownerMapping), null);
                        }
                        jdbcPosition += ownerMapping.getNumberOfColumnMappings();
                    }
                    else
                    {
                        jdbcPosition = BackingStoreHelper.populateOwnerInStatement(sm, ec, ps, jdbcPosition, this);
                    }
                    jdbcPosition = BackingStoreHelper.populateValueInStatement(ec, ps, value, jdbcPosition, valueMapping);

                    sqlControl.executeStatementUpdate(ec, mconn, updateFkStmt, ps, true);
                    retval = true;
                }
                finally
                {
                    sqlControl.closeStatement(mconn, ps);
                }
            }
            finally
            {
                mconn.release();
            }
        }
        catch (SQLException e)
        {
            throw new NucleusDataStoreException(Localiser.msg("056027",updateFkStmt),e);
        }

        return retval;
    }

    protected boolean updateKeyFkInternal(DNStateManager sm, Object key, Object owner)
    {
        boolean retval;
        ExecutionContext ec = sm.getExecutionContext();
        try
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                PreparedStatement ps = sqlControl.getStatementForUpdate(mconn, updateFkStmt, false);
                try
                {
                    int jdbcPosition = 1;
                    if (owner == null)
                    {
                        if (ownerMemberMetaData != null)
                        {
                            ownerMapping.setObject(ec, ps, MappingHelper.getMappingIndices(1,ownerMapping), null, 
                                sm, ownerMemberMetaData.getAbsoluteFieldNumber());
                        }
                        else
                        {
                            ownerMapping.setObject(ec, ps, MappingHelper.getMappingIndices(1,ownerMapping), null);
                        }
                        jdbcPosition += ownerMapping.getNumberOfColumnMappings();
                    }
                    else
                    {
                        jdbcPosition = BackingStoreHelper.populateOwnerInStatement(sm, ec, ps, jdbcPosition, this);
                    }
                    jdbcPosition = BackingStoreHelper.populateKeyInStatement(ec, ps, key, jdbcPosition, keyMapping);

                    sqlControl.executeStatementUpdate(ec, mconn, updateFkStmt, ps, true);
                    retval = true;
                }
                finally
                {
                    sqlControl.closeStatement(mconn, ps);
                }
            }
            finally
            {
                mconn.release();
            }
        }
        catch (SQLException e)
        {
            throw new NucleusDataStoreException(Localiser.msg("056027",updateFkStmt),e);
        }

        return retval;
    }

    /**
     * Method to retrieve a value from the Map given the key.
     * @param ownerSM StateManager for the owner of the map.
     * @param key The key to retrieve the value for.
     * @return The value for this key
     * @throws NoSuchElementException if the key was not found
     */
    protected V getValue(DNStateManager ownerSM, Object key)
    throws NoSuchElementException
    {
        if (!validateKeyForReading(ownerSM, key))
        {
            return null;
        }

        ExecutionContext ec = ownerSM.getExecutionContext();
        if (getStmtLocked == null)
        {
            synchronized (this) // Make sure this completes in case another thread needs the same info
            {
                // Generate the statement, and statement mapping/parameter information
                SQLStatement sqlStmt = getSQLStatementForGet(ownerSM);
                getStmtUnlocked = sqlStmt.getSQLText().toSQL();
                sqlStmt.addExtension(SQLStatement.EXTENSION_LOCK_FOR_UPDATE, true);
                getStmtLocked = sqlStmt.getSQLText().toSQL();
            }
        }

        Boolean serializeRead = ec.getTransaction().getSerializeRead();
        String stmt = (serializeRead != null && serializeRead ? getStmtLocked : getStmtUnlocked);
        Object value = null;
        try
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                // Create the statement and supply owner/key params
                PreparedStatement ps = sqlControl.getStatementForQuery(mconn, stmt);

                StatementMappingIndex ownerIdx = getMappingParams.getMappingForParameter("owner");
                int numParams = ownerIdx.getNumberOfParameterOccurrences();
                for (int paramInstance=0;paramInstance<numParams;paramInstance++)
                {
                    ownerIdx.getMapping().setObject(ec, ps, ownerIdx.getParameterPositionsForOccurrence(paramInstance), ownerSM.getObject());
                }

                StatementMappingIndex keyIdx = getMappingParams.getMappingForParameter("key");
                numParams = keyIdx.getNumberOfParameterOccurrences();
                for (int paramInstance=0;paramInstance<numParams;paramInstance++)
                {
                    keyIdx.getMapping().setObject(ec, ps, keyIdx.getParameterPositionsForOccurrence(paramInstance), key);
                }

                try
                {
                    ResultSet rs = sqlControl.executeStatementQuery(ec, mconn, stmt, ps);
                    try
                    {
                        boolean found = rs.next();
                        if (!found)
                        {
                            throw new NoSuchElementException();
                        }

                        if (valuesAreEmbedded || valuesAreSerialised)
                        {
                            // Embedded/serialised into table of key
                            int param[] = new int[valueMapping.getNumberOfColumnMappings()];
                            for (int i = 0; i < param.length; ++i)
                            {
                                param[i] = i + 1;
                            }

                            if (valueMapping instanceof SerialisedPCMapping ||
                                valueMapping instanceof SerialisedReferenceMapping ||
                                valueMapping instanceof EmbeddedKeyPCMapping)
                            {
                                // Value = Serialised
                                String msg = "You appear to have a map (persistable) value serialised/embedded into the key of the map at " + 
                                    getOwnerMemberMetaData().getFullFieldName() + " Not supported";
                                NucleusLogger.PERSISTENCE.error(msg);
                                throw new NucleusDataStoreException(msg);
                            }

                            // Value = Non-PC
                            value = valueMapping.getObject(ec, rs, param);
                        }
                        else if (valueMapping instanceof ReferenceMapping)
                        {
                            // Value = Reference (Interface/Object)
                            int param[] = new int[valueMapping.getNumberOfColumnMappings()];
                            for (int i = 0; i < param.length; ++i)
                            {
                                param[i] = i + 1;
                            }
                            value = valueMapping.getObject(ec, rs, param);
                        }
                        else
                        {
                            // Value = PC
                            value = new PersistentClassROF<>(ec, rs, ec.getFetchPlan(), getMappingDef, valueCmd, clr.classForName(valueType)).getObject();
                        }

                        JDBCUtils.logWarnings(rs);
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                finally
                {
                    sqlControl.closeStatement(mconn, ps);
                }
            }
            finally
            {
                mconn.release();
            }
        }
        catch (SQLException e)
        {
            throw new NucleusDataStoreException(Localiser.msg("056014", stmt), e);
        }
        return (V) value;
    }

    /**
     * Method to return an SQLStatement for retrieving the value for a key.
     * Selects the join table and optionally joins to the value table if it has its own table.
     * @param ownerSM StateManager for the owning object
     * @return The SQLStatement
     */
    protected SelectStatement getSQLStatementForGet(DNStateManager ownerSM)
    {
        SelectStatement sqlStmt = null;
        ExecutionContext ec = ownerSM.getExecutionContext();

        final ClassLoaderResolver clr = ownerSM.getExecutionContext().getClassLoaderResolver();
        final Class valueCls = clr.classForName(this.valueType);
        if (ownerMemberMetaData.getMap().getMapType() == MapType.MAP_TYPE_KEY_IN_VALUE)
        {
            getMappingDef = new StatementClassMapping();
            if (mapTable.getDiscriminatorMetaData() != null && mapTable.getDiscriminatorMetaData().getStrategy() != DiscriminatorStrategy.NONE)
            {
                // Value class has discriminator
                if (ClassUtils.isReferenceType(valueCls))
                {
                    String[] clsNames = storeMgr.getNucleusContext().getMetaDataManager().getClassesImplementingInterface(valueType, clr);
                    Class[] cls = new Class[clsNames.length];
                    for (int i=0; i<clsNames.length; i++)
                    {
                        cls[i] = clr.classForName(clsNames[i]);
                    }
                    sqlStmt = new DiscriminatorStatementGenerator(storeMgr, clr, cls, true, null, null).getStatement(ec);
                }
                else
                {
                    sqlStmt = new DiscriminatorStatementGenerator(storeMgr, clr, valueCls, true, null, null).getStatement(ec);
                }
                iterateUsingDiscriminator = true;
            }
            else
            {
                // Use union to resolve any subclasses of value
                UnionStatementGenerator stmtGen = new UnionStatementGenerator(storeMgr, clr, valueCls, true, null, null);
                stmtGen.setOption(SelectStatementGenerator.OPTION_SELECT_DN_TYPE);
                getMappingDef.setNucleusTypeColumnName(UnionStatementGenerator.DN_TYPE_COLUMN);
                sqlStmt = stmtGen.getStatement(ec);
            }

            // Select the value field(s)
            SQLStatementHelper.selectFetchPlanOfSourceClassInStatement(sqlStmt, getMappingDef, ec.getFetchPlan(), sqlStmt.getPrimaryTable(), valueCmd, ec.getFetchPlan().getMaxFetchDepth());
        }
        else
        {
            // Value is in key table
            sqlStmt = new SelectStatement(storeMgr, mapTable, null, null);
            sqlStmt.setClassLoaderResolver(clr);

            if (valueCmd != null)
            {
                // Left outer join to value table (so we allow for null values)
                SQLTable valueSqlTbl = sqlStmt.join(JoinType.LEFT_OUTER_JOIN, sqlStmt.getPrimaryTable(), valueMapping, mapTable, null, mapTable.getIdMapping(), 
                    null, null, true);

                // Select the value field(s)
                SQLStatementHelper.selectFetchPlanOfSourceClassInStatement(sqlStmt, getMappingDef, ec.getFetchPlan(), valueSqlTbl, valueCmd, ec.getFetchPlan().getMaxFetchDepth());
            }
            else
            {
                sqlStmt.select(sqlStmt.getPrimaryTable(), valueMapping, null);
            }
        }

        // Apply condition on owner field to filter by owner
        SQLExpressionFactory exprFactory = storeMgr.getSQLExpressionFactory();
        SQLTable ownerSqlTbl = SQLStatementHelper.getSQLTableForMappingOfTable(sqlStmt, sqlStmt.getPrimaryTable(), ownerMapping);
        SQLExpression ownerExpr = exprFactory.newExpression(sqlStmt, ownerSqlTbl, ownerMapping);
        SQLExpression ownerVal = exprFactory.newLiteralParameter(sqlStmt, ownerMapping, null, "OWNER");
        sqlStmt.whereAnd(ownerExpr.eq(ownerVal), true);

        // Apply condition on key
        if (keyMapping instanceof SerialisedMapping)
        {
            // if the keyMapping contains a BLOB column (or any other column not supported by the database
            // as primary key), uses like instead of the operator OP_EQ (=)
            // in future do not check if the keyMapping is of ObjectMapping, but use the database 
            // adapter to check the data types not supported as primary key
            // if object mapping (BLOB) use like
            SQLExpression keyExpr = exprFactory.newExpression(sqlStmt, sqlStmt.getPrimaryTable(), keyMapping);
            SQLExpression keyVal = exprFactory.newLiteralParameter(sqlStmt, keyMapping, null, "KEY");
            sqlStmt.whereAnd(new org.datanucleus.store.rdbms.sql.expression.BooleanExpression(keyExpr, Expression.OP_LIKE, keyVal), true);
        }
        else
        {
            SQLExpression keyExpr = exprFactory.newExpression(sqlStmt, sqlStmt.getPrimaryTable(), keyMapping);
            SQLExpression keyVal = exprFactory.newLiteralParameter(sqlStmt, keyMapping, null, "KEY");
            sqlStmt.whereAnd(keyExpr.eq(keyVal), true);
        }

        // Input parameter(s) - owner, key
        int inputParamNum = 1;
        StatementMappingIndex ownerIdx = new StatementMappingIndex(ownerMapping);
        StatementMappingIndex keyIdx = new StatementMappingIndex(keyMapping);
        if (sqlStmt.getNumberOfUnions() > 0)
        {
            // Add parameter occurrence for each union of statement
            for (int j=0;j<sqlStmt.getNumberOfUnions()+1;j++)
            {
                int[] ownerPositions = new int[ownerMapping.getNumberOfColumnMappings()];
                for (int k=0;k<ownerPositions.length;k++)
                {
                    ownerPositions[k] = inputParamNum++;
                }
                ownerIdx.addParameterOccurrence(ownerPositions);

                int[] keyPositions = new int[keyMapping.getNumberOfColumnMappings()];
                for (int k=0;k<keyPositions.length;k++)
                {
                    keyPositions[k] = inputParamNum++;
                }
                keyIdx.addParameterOccurrence(keyPositions);
            }
        }
        else
        {
            int[] ownerPositions = new int[ownerMapping.getNumberOfColumnMappings()];
            for (int k=0;k<ownerPositions.length;k++)
            {
                ownerPositions[k] = inputParamNum++;
            }
            ownerIdx.addParameterOccurrence(ownerPositions);

            int[] keyPositions = new int[keyMapping.getNumberOfColumnMappings()];
            for (int k=0;k<keyPositions.length;k++)
            {
                keyPositions[k] = inputParamNum++;
            }
            keyIdx.addParameterOccurrence(keyPositions);
        }
        getMappingParams = new StatementParameterMapping();
        getMappingParams.addMappingForParameter("owner", ownerIdx);
        getMappingParams.addMappingForParameter("key", keyIdx);

        return sqlStmt;
    }

    @Override
    public boolean updateEmbeddedKey(DNStateManager sm, Object key, int fieldNumber, Object newValue)
    {
        // We don't support embedded keys as such
        return false;
    }

    @Override
    public boolean updateEmbeddedValue(DNStateManager sm, Object value, int fieldNumber, Object newValue)
    {
        // We don't support embedded values as such
        return false;
    }
}