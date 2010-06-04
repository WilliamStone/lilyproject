package org.lilycms.indexer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrInputDocument;
import org.lilycms.indexer.conf.DerefValue;
import org.lilycms.indexer.conf.IndexCase;
import org.lilycms.indexer.conf.IndexField;
import org.lilycms.indexer.conf.IndexerConf;
import org.lilycms.linkindex.LinkIndex;
import org.lilycms.queue.api.LilyQueue;
import org.lilycms.queue.api.QueueListener;
import org.lilycms.queue.api.QueueMessage;
import org.lilycms.repository.api.*;
import org.lilycms.repository.api.FieldTypeNotFoundException;
import org.lilycms.repository.api.RecordNotFoundException;
import org.lilycms.repository.api.RecordTypeNotFoundException;
import org.lilycms.repository.api.RepositoryException;
import org.lilycms.repoutil.RecordEvent;
import org.lilycms.repoutil.VersionTag;
import org.lilycms.util.ObjectUtils;

import static org.lilycms.repoutil.EventType.*;

import java.io.IOException;
import java.util.*;

/**
 * Updates SOLR index in response to repository events.
 */
public class Indexer {
    private IndexerConf conf;
    private LilyQueue queue;
    private Repository repository;
    private TypeManager typeManager;
    private SolrServer solrServer;
    private LinkIndex linkIndex;
    private IndexerListener indexerListener = new IndexerListener();

    private Log log = LogFactory.getLog(getClass());

    public Indexer(IndexerConf conf, LilyQueue queue, Repository repository, TypeManager typeManager,
            SolrServer solrServer, LinkIndex linkIndex) {
        this.conf = conf;
        this.queue = queue;
        this.repository = repository;
        this.solrServer = solrServer;
        this.typeManager = typeManager;
        this.linkIndex = linkIndex;

        queue.addListener("indexer", indexerListener);
    }

    public void stop() {
        queue.removeListener(indexerListener);
    }

    private class IndexerListener implements QueueListener {
        public void processMessage(String id) {
            try {
                QueueMessage msg = queue.getMessage(id);

                if (!msg.getType().equals(EVENT_RECORD_CREATED) &&
                        !msg.getType().equals(EVENT_RECORD_UPDATED) &&
                        !msg.getType().equals(EVENT_RECORD_DELETED)) {
                    // It is not one of the kinds of events we are interested in
                    return;
                }

                if (msg.getType().equals(EVENT_RECORD_DELETED)) {
                    // For deleted records, we cannot determine the record type, so we do not know if there was
                    // an applicable index case, so we always send a delete to SOLR.
                    solrServer.deleteByQuery("@@id:" + ClientUtils.escapeQueryChars(msg.getRecordId().toString()));

                    if (log.isDebugEnabled()) {
                        log.debug(String.format("Record %1$s: deleted from index (if present) because of " +
                                "delete record event", msg.getRecordId()));
                    }

                    // After this we can go to update denormalized data
                    RecordEvent event = new RecordEvent(msg.getType(), msg.getData());
                    updateDenormalizedData(msg.getRecordId(), event, null, null);
                } else {
                    handleRecordCreateUpdate(msg);
                }


            } catch (Exception e) {
                // TODO
                e.printStackTrace();
            }
        }
    }

    private void handleRecordCreateUpdate(QueueMessage msg) throws Exception {
        RecordEvent event = new RecordEvent(msg.getType(), msg.getData());
        IdRecord record = repository.readWithIds(msg.getRecordId(), null, null);

        // Read the vtags of the record. Note that while this algorithm is running, the record can meanwhile
        // undergo changes. However, we continuously work with the snapshot of the vtags mappings read here.
        // The processing of later events will bring the index up to date with any new changes.
        Map<String, Long> vtags = VersionTag.getTagsById(record, typeManager);
        Map<Long, Set<String>> vtagsByVersion = VersionTag.tagsByVersion(vtags);

        Map<Scope, Set<FieldType>> updatedFieldsByScope = getFieldTypeAndScope(event.getUpdatedFields());

        // Determine the IndexCase:
        //  The indexing of all versions is determined by the record type of the non-versioned scope.
        //  This makes that the indexing behavior of all versions is equal, and can be changed (the
        //  record type of the versioned scope is immutable).
        IndexCase indexCase = conf.getIndexCase(record.getRecordTypeId(), record.getId().getVariantProperties());

        if (indexCase == null) {
            // The record should not be indexed
            // But data from this record might be denormalized into other index entries
            // After this we go to update denormalized data
        } else {
            Set<String> vtagsToIndex = new HashSet<String>();

            if (msg.getType().equals(EVENT_RECORD_CREATED)) {
                // New record: just index everything
                setIndexAllVTags(vtagsToIndex, vtags, indexCase, record);
                // After this we go to the indexing

            } else if (event.getRecordTypeChanged()) {
                // When the record type changes, the rules to index (= the IndexCase) change

                // Delete everything: we do not know the previous record type, so we do not know what
                // version tags were indexed, so we simply delete everything
                solrServer.deleteByQuery("@@id:" + ClientUtils.escapeQueryChars(record.getId().toString()));

                if (log.isDebugEnabled()) {
                    log.debug(String.format("Record %1$s: deleted existing entries from index (if present) " +
                            "because of record type change", record.getId()));
                }

                // Reindex all needed vtags
                setIndexAllVTags(vtagsToIndex, vtags, indexCase, record);

                // After this we go to the indexing
            } else { // a normal update

                if (event.getVersionCreated() == 1
                        && msg.getType().equals(EVENT_RECORD_UPDATED)
                        && indexCase.getIndexVersionless()) {
                    // If the first version was created, but the record was not new, then there
                    // might already be an @@versionless index entry
                    solrServer.deleteById(getIndexId(record.getId(), VersionTag.VERSIONLESS_TAG));

                    if (log.isDebugEnabled()) {
                        log.debug(String.format("Record %1$s: deleted versionless entry from index (if present) " +
                                "because of creation first version", record.getId()));
                    }
                }

                //
                // Handle changes to non-versioned fields
                //
                if (updatedFieldsByScope.get(Scope.NON_VERSIONED).size() > 0) {
                    if (atLeastOneUsedInIndex(updatedFieldsByScope.get(Scope.NON_VERSIONED))) {
                        setIndexAllVTags(vtagsToIndex, vtags, indexCase, record);
                        // After this we go to the treatment of changed vtag fields
                        if (log.isDebugEnabled()) {
                            log.debug(String.format("Record %1$s: non-versioned fields changed, will reindex all vtags.",
                                    record.getId()));
                        }
                    }
                }

                //
                // Handle changes to versioned(-mutable) fields
                //
                // If there were non-versioned fields changed, then we already reindex all versions
                // so this can be skipped.
                //
                if (vtagsToIndex.isEmpty() && (event.getVersionCreated() != -1 || event.getVersionUpdated() != -1)) {
                    if (atLeastOneUsedInIndex(updatedFieldsByScope.get(Scope.VERSIONED))
                            || atLeastOneUsedInIndex(updatedFieldsByScope.get(Scope.VERSIONED_MUTABLE))) {

                        long version = event.getVersionCreated() != -1 ? event.getVersionCreated() : event.getVersionUpdated();
                        if (vtagsByVersion.containsKey(version)) {
                            Set<String> tmp = new HashSet<String>();
                            tmp.addAll(indexCase.getVersionTags());
                            tmp.retainAll(vtagsByVersion.get(version));
                            vtagsToIndex.addAll(tmp);

                            if (log.isDebugEnabled()) {
                                log.debug(String.format("Record %1$s: versioned(-mutable) fields changed, will " +
                                        "index for all tags of modified version %2$s that require indexing: %3$s",
                                        record.getId(), version, vtagSetToNameString(vtagsToIndex)));
                            }
                        }
                    }
                }

                //
                // Handle changes to vtag fields themselves
                //
                Set<String> changedVTagFields = VersionTag.filterVTagFields(event.getUpdatedFields(), typeManager);
                // Remove the vtags which are going to be reindexed anyway
                changedVTagFields.removeAll(vtagsToIndex);
                for (String vtag : changedVTagFields) {
                    if (vtags.containsKey(vtag) && indexCase.getVersionTags().contains(vtag)) {
                        if (log.isDebugEnabled()) {
                            log.debug(String.format("Record %1$s: will index for created or updated vtag %2$s",
                                    record.getId(), safeLoadTagName(vtag)));
                        }
                        vtagsToIndex.add(vtag);
                    } else {
                        // The vtag does not exist anymore on the document: delete from index
                        solrServer.deleteById(getIndexId(record.getId(), vtag));
                        if (log.isDebugEnabled()) {
                            log.debug(String.format("Record %1$s: deleted from index for deleted vtag %2$s",
                                    record.getId(), safeLoadTagName(vtag)));
                        }
                    }
                }
            }


            //
            // Index
            //
            if (vtagsToIndex.contains(VersionTag.VERSIONLESS_TAG)) {
                // Usually when the @@versionless vtag should be indexed, the vtagsToIndex set will
                // not contain any other tags.
                // It could be that there are other tags however: for example if someone added and removed
                // vtag fields to the (versionless) document.
                // If we would ever support deleting of versions, then it could also be the case,
                // but then we'll have to extend this to delete these old versions from the index.
                index(record, Collections.singleton(VersionTag.VERSIONLESS_TAG));
            } else {
                indexRecord(record.getId(), vtagsToIndex, vtags);
            }
        }

        updateDenormalizedData(msg.getRecordId(), event, updatedFieldsByScope, vtagsByVersion);

    }

    /**
     * Indexes a record for a set of vtags.
     *
     * @param vtagsToIndex all vtags for which to index the record, not all vtags need to exist on the record,
     *                     but this should only contain appropriate vtags as defined by the IndexCase for this record.
     * @param vtags the actual vtag mappings of the record
     */
    private void indexRecord(RecordId recordId, Set<String> vtagsToIndex, Map<String, Long> vtags)
            throws IOException, SolrServerException, FieldTypeNotFoundException, RepositoryException,
            RecordTypeNotFoundException {
        // One version might have multiple vtags, so to index we iterate the version numbers
        // rather than the vtags
        Map<Long, Set<String>> vtagsToIndexByVersion = getVtagsByVersion(vtagsToIndex, vtags);
        for (Map.Entry<Long, Set<String>> entry : vtagsToIndexByVersion.entrySet()) {
            IdRecord version = null;
            try {
                version = repository.readWithIds(recordId, entry.getKey(), null);
            } catch (RecordNotFoundException e) {
                // TODO handle this differently from version not found
            }

            if (version == null) {
                for (String vtag : entry.getValue()) {
                    solrServer.deleteById(getIndexId(recordId, vtag));
                }

                if (log.isDebugEnabled()) {
                    log.debug(String.format("Record %1$s, version %2$s: does not exist, deleted index" +
                            " entries for vtags %3$s", recordId, entry.getKey(), vtagSetToNameString(entry.getValue())));
                }
            } else {
                index(version, entry.getValue());
            }
        }
    }

    private void updateDenormalizedData(RecordId recordId, RecordEvent event,
            Map<Scope, Set<FieldType>> updatedFieldsByScope, Map<Long, Set<String>> vtagsByVersion) {

        // This algorithm is designed to first collect all the reindex-work, and then to perform it.
        // Otherwise the same document would be indexed multiple times if it would become invalid
        // because of different reasons (= different indexFields).

        //
        // Collect all the relevant IndexFields, and for each the relevant vtags
        //

        // This map will contain all the IndexFields we need to treat, and for each one the vtags to be considered
        Map<IndexField, Set<String>> indexFieldsVTags = new IdentityHashMap<IndexField, Set<String>>() {
            @Override
            public Set<String> get(Object key) {
                if (!this.containsKey(key) && key instanceof IndexField) {
                    this.put((IndexField)key, new HashSet<String>());
                }
                return super.get(key);
            }
        };

        // There are two cases when denormalized data needs updating:
        //   1. when the content of a (vtagged) record changes
        //   2. when vtags change (are added, removed or point to a different version)
        // We now handle these 2 cases.

        // === Case 1 === updates in response to changes to this record

        long version = event.getVersionCreated() == -1 ? event.getVersionUpdated() : event.getVersionCreated();

        // Determine the relevant index fields
        List<IndexField> indexFields;
        if (event.getType() == RecordEvent.Type.DELETE) {
            indexFields = conf.getDerefIndexFields();
        } else {
            indexFields = new ArrayList<IndexField>();

            collectDerefIndexFields(updatedFieldsByScope.get(Scope.NON_VERSIONED), indexFields);

            if (version != -1 && vtagsByVersion.get(version) != null) {
                collectDerefIndexFields(updatedFieldsByScope.get(Scope.VERSIONED), indexFields);
                collectDerefIndexFields(updatedFieldsByScope.get(Scope.VERSIONED_MUTABLE), indexFields);
            }
        }

        // For each indexField, determine the vtags of the referrer that we should consider.
        // In the context of this algorithm, a referrer is each record whose index might contain
        // denormalized data from the record of which we are now processing the change event.
        nextIndexField:
        for (IndexField indexField : indexFields) {
            DerefValue derefValue = (DerefValue)indexField.getValue();
            FieldType fieldType;
            try {
                fieldType = typeManager.getFieldTypeById(derefValue.getTargetField());
            } catch (FieldTypeNotFoundException e) {
                // Field type does not exist: this is an error in the config
                log.error("The following field type, used in the indexer config, does not exist: " +
                        derefValue.getTargetField());
                continue nextIndexField;
            } catch (RepositoryException e) {
                log.error("Problem loading field type: " + derefValue.getTargetField());
                continue nextIndexField;
            }

            //
            // Determine the vtags of the referrer that we should consider
            //
            Set<String> referrerVtags = indexFieldsVTags.get(indexField);

            // we do not know if the referrer has any versions at all, so always add the versionless tag
            referrerVtags.add(VersionTag.VERSIONLESS_TAG);

            if (fieldType.getScope() == Scope.NON_VERSIONED || event.getType() == RecordEvent.Type.DELETE) {
                // If it is a non-versioned field, then all vtags should be considered.
                // If it is a delete event, we do not know what vtags existed for the record, so consider them all.
                referrerVtags.addAll(conf.getVtags());
            } else {
                // Otherwise only the vtags of the created/updated version, if any
                if (version != -1) {
                    Set<String> vtags = vtagsByVersion.get(version);
                    if (vtags != null)
                        referrerVtags.addAll(vtags);
                }
            }
        }


        // === Case 2 === handle updated/added/removed vtags

        Set<String> changedVTagFields = VersionTag.filterVTagFields(event.getUpdatedFields(), typeManager);
        if (!changedVTagFields.isEmpty()) {
            // In this case, the IndexFields which we need to handle are those that use fields from:
            //  - the previous version to which the vtag pointed (if it is not a new vtag)
            //  - the new version to which the vtag points (if it is not a deleted vtag)
            // But rather than calculating all that (consider the need to retrieve the versions),
            // for now we simply consider all IndexFields.
            // TODO could optimize this to exclude deref fields that use only non-versioned fields?
            for (IndexField indexField : conf.getDerefIndexFields()) {
                indexFieldsVTags.get(indexField).addAll(changedVTagFields);
            }
        }

        //
        // Now search the referrers, that is: for each link field, find out which records point to the current record
        // in a certain versioned view (= a certain vtag)
        //

        // This map holds the referrer records to reindex, and for which versions (vtags) they need to be reindexed. 
        Map<RecordId, Set<String>> referrersVTags = new HashMap<RecordId, Set<String>>() {
            @Override
            public Set<String> get(Object key) {
                if (!containsKey(key) && key instanceof RecordId) {
                    put((RecordId)key, new HashSet<String>());
                }
                return super.get(key);
            }
        };

        // Run over the IndexFields
        nextIndexField:
        for (Map.Entry<IndexField, Set<String>> entry : indexFieldsVTags.entrySet()) {
            IndexField indexField = entry.getKey();
            Set<String> referrerVTags = entry.getValue();
            DerefValue derefValue = (DerefValue)indexField.getValue();

            // Run over the version tags
            for (String referrerVtag : referrerVTags) {
                List<DerefValue.Follow> follows = derefValue.getFollows();

                Set<RecordId> referrers = new HashSet<RecordId>();
                referrers.add(recordId);

                for (int i = follows.size() - 1; i >= 0; i--) {
                    DerefValue.Follow follow = follows.get(i);

                    Set<RecordId> newReferrers = new HashSet<RecordId>();

                    if (follow instanceof DerefValue.FieldFollow) {
                        String fieldId = ((DerefValue.FieldFollow)follow).getFieldId();
                        for (RecordId referrer : referrers) {
                            try {
                                Set<RecordId> linkReferrers = linkIndex.getReferrers(referrer, referrerVtag, fieldId);
                                newReferrers.addAll(linkReferrers);
                            } catch (IOException e) {
                                // TODO
                                e.printStackTrace();
                            }
                        }
                    } else if (follow instanceof DerefValue.VariantFollow) {
                        DerefValue.VariantFollow varFollow = (DerefValue.VariantFollow)follow;
                        Set<String> dimensions = varFollow.getDimensions();

                        // We need to find out the variants of the current set of referrers which have the
                        // same variant properties as the referrer (= same key/value pairs) and additionally
                        // have the extra dimensions defined in the VariantFollow.

                        nextReferrer:
                        for (RecordId referrer : referrers) {

                            Map<String, String> refprops = referrer.getVariantProperties();

                            // If the referrer already has one of the dimensions, then skip it
                            for (String dimension : dimensions) {
                                if (refprops.containsKey(dimension))
                                    continue nextReferrer;
                            }

                            //
                            Set<RecordId> variants;
                            try {
                                variants = repository.getVariants(referrer);
                            } catch (RepositoryException e) {
                                // TODO we should probably throw this higher up and let it be handled there
                                throw new RuntimeException(e);
                            }

                            nextVariant:
                            for (RecordId variant : variants) {
                                Map<String, String> varprops = variant.getVariantProperties();

                                // Check it has each of the variant properties of the current referrer record
                                for (Map.Entry<String, String> refprop : refprops.entrySet()) {
                                    if (!ObjectUtils.safeEquals(varprops.get(refprop.getKey()), refprop.getValue())) {
                                        // skip this variant
                                        continue nextVariant;
                                    }
                                }

                                // Check it has the additional dimensions
                                for (String dimension : dimensions) {
                                    if (!varprops.containsKey(dimension))
                                        continue nextVariant;
                                }

                                // We have a hit
                                newReferrers.add(variant);
                            }
                        }
                    } else if (follow instanceof DerefValue.MasterFollow) {
                        for (RecordId referrer : referrers) {
                            // A MasterFollow can only point to masters
                            if (referrer.isMaster()) {
                                Set<RecordId> variants;
                                try {
                                    variants = repository.getVariants(referrer);
                                } catch (RepositoryException e) {
                                    // TODO we should probably throw this higher up and let it be handled there
                                    throw new RuntimeException(e);
                                }

                                variants.remove(referrer);
                                newReferrers.addAll(variants);
                            }
                        }
                    } else {
                        throw new RuntimeException("Unexpected implementation of DerefValue.Follow: " +
                                follow.getClass().getName());
                    }

                    referrers = newReferrers;
                }

                for (RecordId referrer : referrers) {
                    referrersVTags.get(referrer).add(referrerVtag);
                }
            }
        }


        if (log.isDebugEnabled()) {
            log.debug(String.format("Record %1$s: found %2$s records (times vtags) to be updated because they " +
                    "might contain outdated denormalized data.", recordId, referrersVTags.size()));
        }


        //
        // Now re-index all the found referrers
        //
        nextReferrer:
        for (Map.Entry<RecordId, Set<String>> entry : referrersVTags.entrySet()) {
            RecordId referrer = entry.getKey();
            Set<String> vtagsToIndex = entry.getValue();

            IdRecord record = null;
            try {
                // TODO optimize this: we are only interested to know the vtags and to know if the record has versions
                record = repository.readWithIds(referrer, null, null);
            } catch (Exception e) {
                // TODO handle this
                e.printStackTrace();
            }

            IndexCase indexCase = conf.getIndexCase(record.getRecordTypeId(), record.getId().getVariantProperties());
            if (indexCase == null) {
                continue nextReferrer;
            }

            try {
                if (!recordHasVersions(record)) {
                    if (indexCase.getIndexVersionless() && vtagsToIndex.contains(VersionTag.VERSIONLESS_TAG)) {
                        index(record, Collections.singleton(VersionTag.VERSIONLESS_TAG));
                    }
                } else {
                    Map<String, Long> recordVTags = VersionTag.getTagsById(record, typeManager);
                    vtagsToIndex.retainAll(indexCase.getVersionTags());
                    // Only keep vtags which exist on the record
                    vtagsToIndex.retainAll(recordVTags.keySet());
                    indexRecord(record.getId(), vtagsToIndex, recordVTags);
                }
            } catch (Exception e) {
                // TODO handle this
                e.printStackTrace();
            }
        }


    }

    private void collectDerefIndexFields(Set<FieldType> fieldTypes, List<IndexField> indexFields) {
        for (FieldType fieldType : fieldTypes) {
            indexFields.addAll(conf.getDerefIndexFields(fieldType.getId()));
        }
    }

    /**
     * The actual indexing: maps record fields to index fields, and send to SOLR.
     *
     * @param record the correct version of the record, which has the versionTag applied to it
     * @param vtags the version tags under which to index
     */
    private void index(IdRecord record, Set<String> vtags) throws IOException, SolrServerException {

        // Note that it is important the the indexFields are evaluated in order, since multiple
        // indexFields can have the same name and the order of values for multi-value fields can be important.
        //
        // The value of the indexFields is re-evaluated for each vtag. It is only the value of
        // deref-values which can change from vtag to vtag, so we could optimize this by only
        // evaluating those after the first run, but again because we want to maintain order and
        // because a deref-field could share the same name with a non-deref field, we simply
        // re-evaluate all fields for each vtag.
        for (String vtag : vtags) {
            SolrInputDocument solrDoc = new SolrInputDocument();

            boolean valueAdded = false;
            for (IndexField indexField : conf.getIndexFields()) {
                List<String> values = indexField.getValue().eval(record, repository, vtag);
                if (values != null) {
                    for (String value : values) {
                        solrDoc.addField(indexField.getName(), value);
                        valueAdded = true;
                    }
                }
            }

            if (!valueAdded) {
                // No single field was added to the SOLR document.
                // In this case we do not add it to the index.
                // Besides being somewhat logical, it should also be noted that if a record would not contain
                // any (modified) fields that serve as input to indexFields, we would never have arrived here
                // anyway. It is only because some fields potentially would resolve to a value (potentially:
                // because with deref-expressions we are never sure) that we did.

                // There can be a previous entry in the index which we should try to delete
                solrServer.deleteById(getIndexId(record.getId(), vtag));
                
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Record %1$s, vtag %2$s: no index fields produced output, " +
                            "removed from index if present", record.getId(), safeLoadTagName(vtag)));
                }

                continue;
            }


            solrDoc.setField("@@id", record.getId().toString());
            solrDoc.setField("@@key", getIndexId(record.getId(), vtag));
            solrDoc.setField("@@vtag", vtag);

            if (vtag.equals(VersionTag.VERSIONLESS_TAG)) {
                solrDoc.setField("@@versionless", "true");
            }

            solrServer.add(solrDoc);

            if (log.isDebugEnabled()) {
                log.debug(String.format("Record %1$s, vtag %2$s: indexed", record.getId(), safeLoadTagName(vtag)));
            }
        }
    }

    private boolean atLeastOneUsedInIndex(Set<FieldType> fieldTypes) {
        for (FieldType type : fieldTypes) {
            if (conf.isIndexFieldDependency(type.getId())) {
                return true;
            }
        }
        return false;
    }

    private Map<Long, Set<String>> getVtagsByVersion(Set<String> vtagsToIndex, Map<String, Long> vtags) {
        Map<Long, Set<String>> result = new HashMap<Long, Set<String>>();

        for (String vtag : vtagsToIndex) {
            Long version = vtags.get(vtag);
            if (version != null) {
                Set<String> vtagsOfVersion = result.get(version);
                if (vtagsOfVersion == null) {
                    vtagsOfVersion = new HashSet<String>();
                    result.put(version, vtagsOfVersion);
                }
                vtagsOfVersion.add(vtag);
            }
        }

        return result;
    }

    private Map<Scope, Set<FieldType>> getFieldTypeAndScope(Set<String> fieldIds) {
        Map<Scope, Set<FieldType>> result = new HashMap<Scope, Set<FieldType>>();
        for (Scope scope : Scope.values()) {
            result.put(scope, new HashSet<FieldType>());
        }

        for (String fieldId : fieldIds) {
            FieldType fieldType;
            try {
                fieldType = typeManager.getFieldTypeById(fieldId);
            } catch (FieldTypeNotFoundException e) {
                continue;
            } catch (RepositoryException e) {
                // TODO not sure what to do in these kinds of situations
                throw new RuntimeException(e);
            }
            result.get(fieldType.getScope()).add(fieldType);
        }

        return result;
    }

    /**
     * TODO this method is a temporary solution to detect that a record has versions,
     *      should be removed once issue #1 is solved.
     */
    private boolean recordHasVersions(Record record) {
        if (record.getVersion() > 1)
            return true;

        // the procedure below might not be accurate for versions > 1, since
        // all fields might be deleted from later versions (therefore, the check above)
        for (QName fieldName : record.getFields().keySet()) {
            Scope scope;
            try {
                scope = typeManager.getFieldTypeByName(fieldName).getScope();
            } catch (FieldTypeNotFoundException e) {
                // We assume this doesn't occur (this is a temporary method anyway)
                throw new RuntimeException(e);
            }
            if (scope == Scope.VERSIONED || scope == Scope.VERSIONED_MUTABLE) {
                return true;
            }
        }
        return false;
    }

    private void setIndexAllVTags(Set<String> vtagsToIndex, Map<String, Long> vtags, IndexCase indexCase, Record record) {
        if (recordHasVersions(record)) {
            Set<String> tmp = new HashSet<String>();
            tmp.addAll(indexCase.getVersionTags());
            tmp.retainAll(vtags.keySet()); // only keep the vtags which exist in the document
            vtagsToIndex.addAll(tmp);
        } else if (indexCase.getIndexVersionless()) {
            vtagsToIndex.add(VersionTag.VERSIONLESS_TAG);
        }
    }

    private String getIndexId(RecordId recordId, String vtag) {
        return recordId + "-" + vtag;
    }

    /**
     * Lookup name of field type, for use in debug logs. Beware, this might be slow.
     */
    private String safeLoadTagName(String fieldTypeId) {
        if (fieldTypeId == null)
            return "null";
        if (fieldTypeId.equals(VersionTag.VERSIONLESS_TAG))
            return fieldTypeId;

        try {
            return typeManager.getFieldTypeById(fieldTypeId).getName().getName();
        } catch (Throwable t) {
            return "failed to load name";
        }
    }

    private String vtagSetToNameString(Set<String> vtags) {
        StringBuilder builder = new StringBuilder();
        for (String vtag : vtags) {
            if (builder.length() > 0)
                builder.append(", ");
            builder.append(safeLoadTagName(vtag));
        }
        return builder.toString();
    }
}
